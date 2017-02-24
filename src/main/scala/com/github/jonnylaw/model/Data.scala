package com.github.jonnylaw.model

import akka.stream.scaladsl._
import akka.stream._
import akka.NotUsed
import akka.util.ByteString
import breeze.stats.distributions.{Rand, Exponential, Process, MarkovChain, Uniform}
import breeze.numerics.{exp, sqrt}
import java.nio.file._
import scala.concurrent.Future

/**
  * A single observation of a time series
  */
sealed trait Data {
  val t: Time
  val observation: Observation
}

/**
  * A single observation of a time series, containing a realisation of the filtering state 
  * @param sdeState = x_t = p(x_t | x_t-1), the latent state
  * @param gamma = f(x_t), the latent state transformed by the linear transformation
  * @param eta = g(gamma), the latent state transformed by the linking-function
  * @param observation = pi(eta), the observation
  * @param t, the time of the observation
  */
case class ObservationWithState(
  t: Time,
  observation: Observation,
  eta: Eta,
  gamma: Gamma,
  sdeState: State) extends Data

/**
  * A single observation of a time series
  * @param t, the time of the observation
  * @param observation = pi(eta), the observation
  */
case class TimedObservation(t: Time, observation: Observation) extends Data

trait DataService[F] {
  def observations: Source[Data, F]
}

case class SimulateData(model: Model) extends DataService[NotUsed] {
  def observations: Source[Data, NotUsed] = simRegular(0.1)

  /**
    * Simulate a single step from a model, return a distribution over the possible values
    * of the next step
    * @param deltat the time difference between the previous and next realisation of the process
    * @return a function from the previous datapoint to a Rand (Monadic distribution) representing
    * the distribution of the next datapoint 
    */
  def simStep(deltat: TimeIncrement) = (d: ObservationWithState) =>  {
    for {
      x1 <- model.sde.stepFunction(deltat)(d.sdeState)
      gamma = model.f(x1, d.t + deltat)
      eta = model.link(gamma)
      y1 <- model.observation(gamma)
    } yield ObservationWithState(d.t + deltat, y1, eta, gamma, x1)
  }

  /**
    * Simulate from a POMP model on an irregular grid, given an initial time and a stream of times 
    * at which simulate from the model
    * @param t0 the start time of the process
    * @return an Akka Flow transforming a Stream from Time to ObservationWithState 
    */
  def simPompModel(t0: Time) = {
    val init = for {
      x0 <- model.sde.initialState
      gamma = model.f(x0, t0)
      eta = model.link(gamma)
      y <- model.observation(gamma)
    } yield ObservationWithState(t0, y, eta, gamma, x0)

    Flow[Time].scan(init.draw)((d0, t: Time) => simStep(t - d0.t)(d0).draw)
  }

  /**
    * Compute an empirical forecast, starting from a filtering distribution estimate
    * @param s a PfState object, the output of a particle filter
    */
  def forecast[F[_]](s: PfState[F])(implicit f: Collection[F]) = {

    val init = f.map(s.particles)(x => {
      val gamma = model.f(x, s.t)
      val eta = model.link(gamma)
      ObservationWithState(s.t, s.observation.getOrElse(0.0), eta, gamma, x)
    })

    Flow[Time].scan(init)((d0, t: Time) => f.map(d0)(x => simStep(t - x.t)(x).draw))
  }

  /**
    * Simulate from a POMP model (not including the Log-Gaussian Cox-Process) 
    * on a regular grid from t = 0 using the MarkovChain from the breeze package
    * @param dt the time increment between sucessive realisations of the POMP model
    * @return a Process, representing a distribution which depends on previous draws
    */
  def simMarkov(dt: TimeIncrement): Process[ObservationWithState] = {
    
    val init = for {
      x0 <- model.sde.initialState
      gamma = model.f(x0, 0.0)
      eta = model.link(gamma)
      y <- model.observation(gamma)
    } yield ObservationWithState(0.0, y, eta, gamma, x0)

    MarkovChain(init.draw)(simStep(dt))
  }

  /**
    * Simulate from any model on a regular grid from t = 0 and return an Akka stream of realisations
    * @param dt the time increment between successive realisations of the POMP model
    * @return an Akka Stream containing a realisation of the process
    */
  def simRegular(dt: TimeIncrement): Source[ObservationWithState, NotUsed] = {
    Source.fromIterator(() => simMarkov(dt).steps)
  }

  /**
    * Simulate the log-Gaussian Cox-Process using thinning
    * @param start the starting time of the process
    * @param the end time of the process
    * @param mod the model to simulate from. In a composition, the LogGaussianCox must be the left-hand model
    * @param precision an integer specifying the timestep between simulating the latent state, 10e-precision
    * @return a vector of Data specifying when events happened
    */
  def simLGCP(
    start: Time,
    end: Time,
    precision: Int): Vector[ObservationWithState] = {

    // generate an SDE Stream
    val stateSpace = SimulateData.simSdeStream(model.sde.initialState.draw,
      start, end - start, precision, model.sde.stepFunction)

    // Calculate the upper bound of the stream
    val upperBound = stateSpace.map(s => model.f(s.state, s.time)).map(exp(_)).max

    def loop(lastEvent: Time, eventTimes: Vector[ObservationWithState]): Vector[ObservationWithState] = {
      // sample from an exponential distribution with the upper bound as the parameter
      val t1 = lastEvent + Exponential(upperBound).draw

      if (t1 > end) {
        eventTimes
      } else {
        // drop the elements we don't need from the stream, then calculate the hazard near that time
        val statet1 = stateSpace.takeWhile(s => s.time <= t1) 
        val hazardt1 = statet1.map(s => model.f(s.state, s.time)).last

        val stateEnd = statet1.last.state
        val gamma = statet1 map (s => model.f(s.state, s.time))
        val eta = exp(gamma.last)

        if (Uniform(0,1).draw <= exp(hazardt1)/upperBound) {
          loop(t1, ObservationWithState(t1, 1.0, eta, gamma.last, stateEnd) +: eventTimes)
         } else {
          loop(t1, eventTimes)
        }
      }
    }
    loop(start, stateSpace.map{ s => {
      val gamma = model.f(s.state, s.time)
      val eta = exp(gamma)
      ObservationWithState(s.time, 0.0, eta, gamma, s.state) }}.toVector
    )
  }
}

object SimulateData {
  /**
    * Simulate a diffusion process as a stream
    * @param x0 the starting value of the stream
    * @param t0 the starting time of the stream
    * @param totalIncrement the ending time of the stream
    * @param precision the step size of the stream 10e(-precision)
    * @param stepFun the stepping function to use to generate the SDE Stream
    * @return a lazily evaluated stream of Sde
    */
  def simSdeStream(
    x0: State,
    t0: Time,
    totalIncrement: TimeIncrement,
    precision: Int,
    stepFunction: TimeIncrement => State => Rand[State]): scala.collection.immutable.Stream[StateSpace] = {

    val deltat: TimeIncrement = Math.pow(10, -precision)

    // define a recursive stream from t0 to t = t0 + totalIncrement stepping by 10e-precision
    scala.collection.immutable.Stream.
      iterate(StateSpace(t0, x0))(x =>
        StateSpace(x.time + deltat, stepFunction(deltat)(x.state).draw)).
      takeWhile(s => s.time <= t0 + totalIncrement)
  }
}

/**
  * Read a csv file in, where the first column corresponds to the Time, represented as a Double
  * and the second column represents the observation.
  * @param file a java.nio.file.Path to a file
  */
case class DataFromFile(file: String) extends DataService[Future[IOResult]] {
  def observations: Source[Data, Future[IOResult]] = {
    FileIO.fromPath(Paths.get(file)).
      via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 8192, allowTruncation = true)).
      map(_.utf8String).
      map(a => a.split(",")).
      map(d => TimedObservation(d(0).toDouble, d(1).toDouble))
  }
}

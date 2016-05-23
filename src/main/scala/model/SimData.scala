package model

import breeze.linalg.DenseVector
import breeze.numerics.{exp, log}
import breeze.stats.distributions.{Gaussian, Uniform, Exponential, Rand}
import model.POMP._
import model.Utilities._
import model.DataTypes._
import model.State._
import breeze.linalg.linspace

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import java.io.File
import akka.stream.scaladsl._
import akka.util.ByteString
import Stream._


object SimData {
  /**
    * Stream of sde simulation which may make computation faster
    * @param x0 the starting value of the stream
    * @param t0 the starting time of the stream
    * @param precision the step size of the stream 10e(-precision)
    * @param stepFun the stepping function to use to generate the SDE Stream
    * @return a lazily evaluated stream from t0
    */
  def simSdeStream(
    x0: State,
    t0: Time,
    totalIncrement: TimeIncrement,
    precision: Int,
    stepFun: (State, TimeIncrement) => Rand[State]): Stream[Sde] = {

    val deltat: TimeIncrement = Math.pow(10, -precision)

    // define a recursive stream from t0 to t = t0 + totalIncrement stepping by 10e-precision
    lazy val stream: Stream[Sde] = (Stream.cons(Sde(t0, x0),
      stream map (x => Sde(x.time + deltat, stepFun(x.state, deltat).draw)))).
      takeWhile (s => s.time <= t0 + totalIncrement)

    stream
  }

  /**
    * We have a specialist function for simulating the log-Gaussian Cox-Process using thinning
    * Can we move some of the logic into the (empty) observation function in the POMP class?
    * No, because we need to be aware of a prior upper bound on the SDE
    */
  def simLGCP(
    start: Time,
    end: Time,
    mod: Model,
    precision: Int): Vector[Data] = {

    // generate an SDE Stream
    val stateSpace = simSdeStream(mod.x0.draw, start, end - start, precision, mod.stepFunction)

    // Calculate the upper bound of the stream
    val upperBound = stateSpace.map(s => mod.f(s.state, s.time)).
      map(exp(_)).max

    def loop(lastEvent: Time, eventTimes: Vector[Data]): Vector[Data] = {
      // sample from an exponential distribution with the upper bound as the parameter
      val t1 = lastEvent + Exponential(upperBound).draw

      if (t1 > end) {
        eventTimes
      } else {
        // drop the elements we don't need from the stream, then calculate the hazard near that time
        val statet1 = stateSpace.takeWhile(s => s.time <= t1) 
        val hazardt1 = statet1.map(s => mod.f(s.state, s.time)).last

        val stateEnd = statet1.last.state
        val gamma = mod.f(stateEnd, t1)
        val eta = mod.link(gamma)

        if (Uniform(0,1).draw <= exp(hazardt1)/upperBound) {
          loop(t1, Data(t1, true, Some(eta), Some(gamma), Some(statet1.last.state)) +: eventTimes)
         } else {
          loop(t1, eventTimes)
        }
      }
    }
    loop(start, stateSpace.map{ s => {
      val gamma = mod.f(s.state, s.time)
      val eta = mod.link(gamma)
      Data(s.time, false, Some(eta), Some(gamma), Some(s.state)) }}.toVector
    )
  }

  /**
    * Generates a vector of event times from the Log-Gaussian Cox-Process
    * by thinning an exponential process
    */
  def simLGCPEvents(
    start: Time,
    end: Time,
    mod: Model,
    precision: Int): Vector[Data] = {

    // generate an SDE Stream
    val stateSpace = simSdeStream(mod.x0.draw, start, end - start, precision, mod.stepFunction)

    // Calculate the upper bound of the stream
    val upperBound = stateSpace.map(s => mod.f(s.state, s.time)).
      map(exp(_)).max

    def loop(lastEvent: Time, eventTimes: Vector[Data]): Vector[Data] = {
      // sample from an exponential distribution with the upper bound as the parameter
      val t1 = lastEvent + Exponential(upperBound).draw

      if (t1 > end) {
        eventTimes.reverse
      } else {
        // drop the elements we don't need from the stream, then calculate the hazard near that time
        val statet1 = stateSpace.takeWhile(s => s.time <= t1) 
        val hazardt1 = statet1.map(s => mod.f(s.state, s.time)).last

        val stateEnd = statet1.last.state
        val gamma = mod.f(stateEnd, t1)
        val eta = mod.link(gamma)

        if (Uniform(0,1).draw <= exp(hazardt1)/upperBound) {
          loop(t1, Data(t1, true, Some(eta), Some(gamma), Some(stateEnd)) +: eventTimes)
         } else {
          loop(t1, eventTimes)
        }
      }
    }
    loop(start, Vector())
  }

  def simStep(
    x0: State,
    t0: Time,
    deltat: TimeIncrement,
    mod: Model): Data = {

    val x1 = mod.stepFunction(x0, deltat).draw
    val gamma = mod.f(x1, t0)
    val eta = mod.link(gamma)
    val y1 = mod.observation(eta).draw
    Data(t0, y1, Some(eta), Some(gamma), Some(x1))
  }

  def simData(times: Seq[Time], mod: Model): Vector[Data] = {

    val x0 = mod.x0.draw
    val d0 = simStep(x0, times.head, 0, mod)

    val data = times.tail.foldLeft(Vector[Data](d0)) { (acc, t) =>
      val deltat = t - acc.head.t
      val x0 = acc.head.sdeState.get
      val d = simStep(x0, t, deltat, mod)
      d +: acc
    }

    data.reverse
  }
}

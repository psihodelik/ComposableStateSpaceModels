package com.github.jonnylaw.model

import DataTypes._
import breeze.stats.distributions.{Rand, Gaussian, MultivariateGaussian}
import breeze.stats.distributions.Rand._

import breeze.linalg.{diag, DenseVector}
import breeze.numerics.{exp, sqrt}

// TODO: Implement Functional Error Handling
sealed trait SdeFunction[P] {
  def step: P => StepFunction
}

// case object BrownianMotion extends SdeFunction[BrownianParameter] {
//   def step(p: BrownianParameter): StepFunction = ???

  // (s, dt) => {
  //   s map (x => MultivariateGaussian(x + p.mu * dt, p.sigma * dt))
  // }
//}

// object SdeFunction {
//   private def unsafeBrownianMotion(params: BrownianParameter): (State, TimeIncrement) => Rand[State] =
//     BrownianMotion.step(params)

//   def brownianMotion(p: SdeParameter): Either[Throwable, StepFunction] = p match {
//     case sdeParam: BrownianParameter => Right(unsafeBrownianMotion(sdeParam))
//     case _ => Left(throw new Exception(s"Incorrect parameters supplied to BrownianMotion, required BrownianParameter received $p"))
//   }
// }

object StateSpace {
  /**
    * Steps all the states using the identity
    * @param p a Parameter
    * @return a function from state, dt => State
    */
  def stepNull(p: Parameters): (State, TimeIncrement) => Rand[State] = {
    (s, dt) => always(State.map(s)(x => x))
  }

  /**
    * A step function for generalised brownian motion, dx_t = mu dt + sigma dW_t
    * @param p an sde parameter
    * @return A function from state, time increment to state
    */
  def stepBrownian(p: SdeParameter): (State, TimeIncrement) => Rand[State] = {
    (s, dt) => p match {
      case BrownianParameter(mu, sigma) => {
        MultivariateGaussian(mu, sigma) map { LeafState(_) }
      }
      case _ => throw new Exception("Incorrect parameters supplied to stepBrownian, expected BrownianParameter")
    }
  }

  /**
    * Steps the state by the value of the parameter "a" 
    * multiplied by the time increment "dt"
    * @param p a parameter Map
    * @return a function from (State, dt) => State, with the
    * states being the same structure before and after
    */
  def stepConstant(p: SdeParameter): (State, TimeIncrement) => Rand[State] = {
    (s, dt) => p match {
      case StepConstantParameter(a) =>
        always(s map (_ + (a :* dt)))
      case _ => throw new Exception("Incorrect Parameters supplied to stepConstant, expected StepConstantParameter")
    }
  }

  /**
    * A step function for the Ornstein Uhlenbeck process dx_t = alpha(theta - x_t) dt + sigma dW_t
    * @param p the parameters of the ornstein uhlenbeck process, theta, alpha and sigma
    * @return
    */
  def stepOrnstein(p: SdeParameter): (State, TimeIncrement) => Rand[State] = {
    (s, dt) => p match {
        case OrnsteinParameter(theta, alpha, sigma) =>
        always(
          s map { x => // calculate the mean of the solution
            val mean = (x.data, alpha.data, theta.data).zipped map { case (state, a, t) => t + (state - t) * exp(- a * dt) }
            // calculate the variance of the solution
            val variance = (sigma.data, alpha.data).zipped map { case (s, a) => (s*s/2*a)*(1-exp(-2*a*dt)) }
            DenseVector(mean.zip(variance) map { case (a, v) => Gaussian(a, sqrt(v)).draw() })
          })
        case _ => throw new Exception("Incorrect parameters supplied to stepOrnstein, expected OrnsteinParameter")
    }
  }
}

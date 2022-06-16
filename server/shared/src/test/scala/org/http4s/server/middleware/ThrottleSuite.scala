/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.server.middleware

import cats.Id
import cats.effect.IO
import cats.effect.Outcome
import cats.effect.testkit.TestControl
import cats.implicits._
import org.http4s.Http
import org.http4s.Http4sSuite
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.dsl.io._
import org.http4s.laws.discipline.arbitrary.genFiniteDuration
import org.http4s.server.middleware.Throttle._
import org.http4s.syntax.all._
import org.scalacheck.effect.PropF.forAllF

import scala.concurrent.duration._

class ThrottleSuite extends Http4sSuite {
  test("LocalTokenBucket should contain initial number of tokens equal to specified capacity") {
    forAllF(genFiniteDuration) { (someRefillTime: FiniteDuration) =>
      val capacity = 5
      val createBucket =
        TokenBucket.local[IO](capacity, someRefillTime)

      val prog = createBucket.flatMap { testee =>
        val takeFiveTokens: IO[List[TokenAvailability]] =
          (1 to 5).toList.traverse(_ => testee.takeToken)
        val checkTokensUpToCapacity =
          takeFiveTokens.map(tokens => tokens.contains(TokenAvailable))
        (checkTokensUpToCapacity, testee.takeToken.map(_.isInstanceOf[TokenUnavailable]))
          .mapN(_ && _)
      }.assert

      TestControl.executeEmbed(prog)
    }
  }

  test("LocalTokenBucket should add another token at specified interval when not at capacity") {
    forAllF(genFiniteDuration) { (someRefillTime: FiniteDuration) =>
      val exceeded = someRefillTime + 1.millisecond

      val capacity = 1
      val createBucket =
        TokenBucket.local[IO](capacity, someRefillTime)

      val takeTokenAfterRefill = createBucket.flatMap { testee =>
        testee.takeToken *> IO.sleep(exceeded) *>
          (IO.realTime, testee.takeToken).mapN((_, _))
      }

      TestControl.executeEmbed(takeTokenAfterRefill).assertEquals((exceeded, TokenAvailable))
    }
  }

  test("LocalTokenBucket should not add another token at specified interval when at capacity") {
    forAllF(genFiniteDuration) { (someRefillTime: FiniteDuration) =>
      val capacity = 5
      val createBucket =
        TokenBucket.local[IO](capacity, someRefillTime)

      val takeExtraToken = createBucket.flatMap { testee =>
        val takeFiveTokens: IO[List[TokenAvailability]] = (1 to 5).toList.traverse { _ =>
          testee.takeToken
        }
        takeFiveTokens >> IO.sleep(300.milliseconds) >> testee.takeToken
      }

      TestControl.execute(takeExtraToken).flatMap { control =>
        for {
          _ <- control.results.assertEquals(None)
          _ <- control.tick
          _ <- control.results.assertEquals(None)
          interval <- control.nextInterval
          _ <- control.advanceAndTick(interval)
          _ <- control.results
            .map(_.exists {
              case Outcome.Succeeded(TokenUnavailable(Some(_))) => true
              case _ => false
            })
            .assert
        } yield ()
      }
    }
  }

  test(
    "LocalTokenBucket should only return a single token when only one token available and there are multiple concurrent requests"
  ) {
    val capacity = 1
    val createBucket =
      TokenBucket.local[IO](capacity, 1.milliseconds)

    val takeTokensSimultaneously = createBucket.flatMap { testee =>
      (1 to 5).toList.parTraverse(_ => testee.takeToken)
    }

    val prog = takeTokensSimultaneously
      .map { result =>
        result.count(_ == TokenAvailable)
      }

    TestControl.executeEmbed(prog).assertEquals(1)
  }

  val localGen = for {
    fd1 <- genFiniteDuration
    fd2 <- genFiniteDuration.suchThat(_ < fd1)
  } yield fd1 -> fd2

  test(
    "LocalTokenBucket should return the time until the next token is available when no token is available"
  ) {
    forAllF(localGen) { case (fd1, fd2) =>
      val capacity = 1
      val createBucket =
        TokenBucket.local[IO](capacity, fd1)

      val takeTwoTokens = createBucket.flatMap { testee =>
        testee.takeToken *> IO.sleep(fd2) *> testee.takeToken
      }

      TestControl.execute(takeTwoTokens).flatMap { control =>
        for {
          _ <- control.results.assertEquals(None)
          _ <- control.tick
          _ <- control.advanceAndTick(fd2)
          _ <- control.results
            .map(_.collect { case Outcome.Succeeded(TokenUnavailable(Some(interval))) =>
              interval
            })
            .map(_.exists(_ == (fd1 - fd2)))
            .assert
        } yield ()
      }
    }
  }

  private val alwaysOkApp = HttpApp[IO] { _ =>
    Ok()
  }

  private val alwaysOkRootRoute = HttpRoutes.of[IO] { case GET -> Root =>
    Ok()
  }

  private def testMiddleware(
      test: ((TokenBucket[IO], Option[FiniteDuration] => Response[IO]) => Http[IO, IO]) => IO[Unit]
  ) =
    for {
      _ <- test(Throttle(_, _)(alwaysOkApp))
      _ <- test(Throttle.httpApp(_, _)(alwaysOkApp))
      _ <- test(Throttle.httpRoutes(_, _)(alwaysOkRootRoute).orNotFound)
    } yield ()

  test("Throttle / should allow a request to proceed when the rate limit has not been reached") {
    testMiddleware { middleware =>
      val limitNotReachedBucket = new TokenBucket[IO] {
        override def takeToken: IO[TokenAvailability] = TokenAvailable.pure[IO]
      }

      val testee = middleware(limitNotReachedBucket, defaultResponse[IO] _)
      val req = Request[IO](uri = uri"/")

      TestControl.executeEmbed(testee(req).map(_.status)).assertEquals(Status.Ok)
    }
  }

  test("Throttle / should deny a request when the rate limit had been reached") {
    testMiddleware { middleware =>
      val limitReachedBucket = new TokenBucket[IO] {
        override def takeToken: IO[TokenAvailability] = TokenUnavailable(None).pure[IO]
      }

      val testee = middleware(limitReachedBucket, defaultResponse[IO] _)
      val req = Request[IO](uri = uri"/")

      TestControl.executeEmbed(testee(req).map(_.status)).assertEquals(Status.TooManyRequests)
    }
  }

  test(
    "Throttle / should deny a request when the rate limit had been reached but the route's not been found"
  ) {
    val limitReachedBucket = new TokenBucket[IO] {
      override def takeToken: IO[TokenAvailability] = TokenUnavailable(None).pure[IO]
    }

    val testee =
      Throttle.httpRoutes(limitReachedBucket, defaultResponse[IO] _)(alwaysOkRootRoute).orNotFound
    val req = Request[IO](uri = uri"/nonexistent")

    TestControl.executeEmbed(testee(req).map(_.status)).assertEquals(Status.TooManyRequests)
  }
}

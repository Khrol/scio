package com.spotify.scio.ml.nn

import breeze.linalg._
import org.scalatest.{Matchers, FlatSpec}

object NearestNeighborTest {
  val numVectors = 1000
  val dimension = 40

  def newVec: DenseVector[Double] = {
    val v = DenseVector.rand[Double](dimension)
    v / norm(v)
  }
}

class NearestNeighborTest extends FlatSpec with NearestNeighborBehaviors {

  import NearestNeighborTest._

  override val testData = (1 to numVectors).map { i =>
    val v = DenseVector.rand[Double](dimension)
    ("k" + i, v :/ norm(v))
  }

  val matrixNN: NearestNeighbor[String, Double] = {
    val b = NearestNeighbor.newMatrixbuilder[String, Double](dimension)
    testData.foreach { case (key, vec) => b.add(key, vec) }
    b.build
  }

  val lshNN: NearestNeighbor[String, Double] = {
    val b = NearestNeighbor.newLSHBuilder[String, Double](dimension, 5, testData.size / 100)
    testData.foreach { case (key, vec) => b.add(key, vec) }
    b.build
  }

  "MatrixNearestNeighbor" should behave like correct(matrixNN)
  "LSHNearestNeighbor" should behave like correct(lshNN)

}

trait NearestNeighborBehaviors extends Matchers { this: FlatSpec =>

  import NearestNeighborTest._

  val testData: Seq[(String, DenseVector[Double])]

  def correct(nn: NearestNeighbor[String, Double]): Unit = {

    it should "respect maxResult" in {
      Seq(1, 10, 100, 1000, 2000).foreach { maxResult =>
        nn.lookup(newVec, maxResult).size should be <= maxResult
      }
    }

    it should "respect minSimilarity" in {
      Seq(-1.0, -0.5, -0.1, 0.0, 0.1, 0.5, 1.0).foreach { minSimilarity =>
        nn.lookup(newVec, 100, minSimilarity).forall(_._2 >= minSimilarity) shouldBe true
      }
    }

    // TODO: figure out accuracy expectation
    it should "retrieve similar results" in {
      val v1 = newVec
      val expected = testData
        .map { case (key, v2) => (key, v1 dot v2) }
        .sortBy(-_._2)
        .take(100)
      val actual = nn.lookup(v1, 100)
      (expected.map(_._1).toSet intersect actual.map(_._1).toSet).size should be >= 75
    }
  }

}
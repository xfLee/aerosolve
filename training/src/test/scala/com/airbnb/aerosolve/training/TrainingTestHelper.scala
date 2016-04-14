package com.airbnb.aerosolve.training

import java.util

import com.airbnb.aerosolve.core.features.{FeatureRegistry, SimpleExample}
import com.airbnb.aerosolve.core.models.{AdditiveModel, SplineModel}
import com.airbnb.aerosolve.core.{Example, FeatureVector}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

object TrainingTestHelper {
  val log = LoggerFactory.getLogger("TrainingTestHelper")

  def makeExample(x : Double,
                  y : Double,
                  target : Double,
                   registry: FeatureRegistry) : Example = {
    val example = new SimpleExample(registry)
    val item: FeatureVector = example.createVector()
    item.putString("BIAS", "B")
    if (x + y < 0) {
      item.putString("NEG", "T")
    }
    item.put("$rank", "", target)
    item.put("loc", "x", x)
    item.put("loc", "y", y)
    example
  }

  def makeSimpleClassificationExamples(registry: FeatureRegistry) = {
    val examples = ArrayBuffer[Example]()
    val label = ArrayBuffer[Double]()
    val rnd = new java.util.Random(1234)
    var numPos: Int = 0
    for (i <- 0 until 200) {
      val x = 2.0 * rnd.nextDouble() - 1.0
      val y = 10.0 * (2.0 * rnd.nextDouble() - 1.0)
      val poly = x + y
      val rank = if (poly < 1.0) {
        1.0
      } else {
        -1.0
      }
      if (rank > 0) numPos = numPos + 1
      label += rank
      examples += makeExample(x, y, rank, registry)
    }
    (examples, label, numPos)
  }

  def makeMulticlassExample(x : Double,
                            y : Double,
                            z : Double,
                            label : (String, Double),
                            label2 : Option[(String, Double)],
                             registry: FeatureRegistry) : Example = {
    val example = new SimpleExample(registry)
    val item: FeatureVector = example.createVector()
    item.putString("BIAS", "B")
    item.put("$rank", label._1, label._2)
    if (label2.isDefined) {
      item.put("$rank", label2.get._1, label2.get._2)
    }
    item.put("loc", "x", x)
    item.put("loc", "y", y)
    item.put("loc", "z", z)
    example
  }

  def makeSimpleMulticlassClassificationExamples(multiLabel : Boolean,
                                                  registry : FeatureRegistry) = {
    val examples = ArrayBuffer[Example]()
    val labels = ArrayBuffer[String]()
    val rnd = new java.util.Random(1234)
    for (i <- 0 until 1000) {
      var x = 2.0 * rnd.nextDouble() - 1.0
      var y = 2.0 * rnd.nextDouble() - 1.0
      val z = 2.0 * rnd.nextDouble() - 1.0
      var label : String = ""
      rnd.nextInt(4) match {
        case 0 => {
          label = "top_left"
          x = x - 10.0
          y = y + 10.0
        }
        case 1 => {
          label = "top_right"
          x = x + 10.0
          y = y + 10.0
        }
        case 2 => {
          label = "bot_left"
          x = x - 10.0
          y = y - 10.0
        }
        case 3 => {
          label = "bot_right"
          x = x + 10.0
          y = y - 10.0
        }
      }
      labels += label
      if (multiLabel) {
        val label2 = if (x > 0) "right" else "left"
        examples += makeMulticlassExample(x, y, z, (label, 1.0), Some((label2, 0.1)), registry)
      } else {
        examples += makeMulticlassExample(x, y, z, (label, 1.0), None, registry)
      }
    }
    (examples, labels)
  }

  def makeNonlinearMulticlassClassificationExamples(registry: FeatureRegistry) = {
    val examples = ArrayBuffer[Example]()
    val labels = ArrayBuffer[String]()
    val rnd = new java.util.Random(1234)
    for (i <- 0 until 1000) {
      val x = 20.0 * rnd.nextDouble() - 10.0
      val y = 20.0 * rnd.nextDouble() - 10.0
      val z = 20.0 * rnd.nextDouble() - 10.0
      val d = math.sqrt(x * x + y * y + z * z)
      // Three nested layers inner, middle and outer
      val label : String = if (d < 5) "inner" else
                           if (d < 10) "middle" else "outer"
      labels += label
      examples += makeMulticlassExample(x, y, z, (label, 1.0), None, registry)
    }
    (examples, labels)
  }

  def makeHybridExample(x : Double,
                        y : Double,
                        target : Double,
                         registry : FeatureRegistry) : Example = {
    val example = new SimpleExample(registry)
    val item: FeatureVector = example.createVector()
    item.put("$rank", "", target)
    item.put("loc", "x", x)
    item.put("loc", "y", y)
    item.put("xy", "xy", x*y)
    example
  }

  def makeClassificationExamples(registry: FeatureRegistry) = {
    val examples = ArrayBuffer[Example]()
    val label = ArrayBuffer[Double]()
    val rnd = new java.util.Random(1234)
    var numPos : Int = 0
    for (i <- 0 until 500) {
      val x = 2.0 * rnd.nextDouble() - 1.0
      val y = 10.0 * (2.0 * rnd.nextDouble() - 1.0)
      val poly = x * x + 0.1 * y * y + 0.1 * x + 0.2 * y - 0.1 + Math.sin(x)
      val rank = if (poly < 1.0) {
        1.0
      } else {
        -1.0
      }
      if (rank > 0) numPos = numPos + 1
      label += rank
      examples += makeExample(x, y, rank, registry)
    }
    (examples, label, numPos)
  }

  def makeLinearClassificationExamples(registry: FeatureRegistry) = {
    val examples = ArrayBuffer[Example]()
    val label = ArrayBuffer[Double]()
    val rnd = new java.util.Random(1234)
    var numPos : Int = 0
    for (i <- 0 until 200) {
      val x = 2.0 * rnd.nextDouble() - 1.0
      val y = 10.0 * (2.0 * rnd.nextDouble() - 1.0)
      val linear = -6.0 * x + y + 3.0 + 2 * x * y
      val rank = if (linear < 1.0) {
        1.0
      } else {
        -1.0
      }
      if (rank > 0) numPos = numPos + 1
      label += rank
      examples += makeHybridExample(x, y, rank, registry)
    }
    (examples, label, numPos)
  }

  def makeRegressionExamples(registry: FeatureRegistry, randomSeed: Int = 1234) = {
    val examples = ArrayBuffer[Example]()
    val label = ArrayBuffer[Double]()
    val rnd = new java.util.Random(randomSeed)

    for (i <- 0 until 200) {
      val x = 4.0 * (2.0 * rnd.nextDouble() - 1.0)
      val y = 4.0 * (2.0 * rnd.nextDouble() - 1.0)

      // Curve will be a "saddle" with flat regions where, for instance, x = 0 and y > 2.06 or y < -1.96
      val flattenedQuadratic = math.max(x * x - 2 * y * y - 0.5 * x + 0.2 * y, -8.0)

      examples += makeExample(x, y, flattenedQuadratic, registry)
      label += flattenedQuadratic
    }

    (examples, label)
  }

  def makeLinearRegressionExamples(registry: FeatureRegistry, randomSeed: Int = 1234) = {
    val examples = ArrayBuffer[Example]()
    val label = ArrayBuffer[Double]()
    val rnd = new java.util.Random(randomSeed)

    for (i <- 0 until 200) {
      val x = 2.0 * (rnd.nextDouble() - 0.5)
      val y = 2.0 * (rnd.nextDouble() - 0.5)
      val z = 0.1 * x * y - 0.5 * x + 0.2 * y + 1.0
      examples += makeHybridExample(x, y, z, registry)
      label += z
    }

    (examples, label)
  }

  def printSpline(model: SplineModel) = {
    val weights = model.getWeightSpline.asScala
    for ((feature, weightSpline) <- weights) {
        log.info(("family=%s,feature=%s,"
                  + "minVal=%f, maxVal=%f, weights=%s")
                   .format(feature.family.name,
                           feature.name,
                           weightSpline.spline.getMinVal,
                           weightSpline.spline.getMaxVal,
                           weightSpline.spline.getWeights.mkString(",")
          )
        )
    }
  }

  def printAdditiveModel(model: AdditiveModel) = {
    val weights = model.weights.asScala
    for ((feature, func) <- weights) {
      log.info("family=%s,feature=%s".format(feature.family.name, feature.name))
      log.info(func.toString)
    }
  }
}
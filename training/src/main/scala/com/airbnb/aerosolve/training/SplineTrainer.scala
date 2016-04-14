package com.airbnb.aerosolve.training

import com.airbnb.aerosolve.core.features._
import com.airbnb.aerosolve.core.models.SplineModel
import com.airbnb.aerosolve.core.models.SplineModel.WeightSpline
import com.airbnb.aerosolve.core.util.Util
import com.airbnb.aerosolve.core.{Example, FeatureVector}
import com.airbnb.aerosolve.training.CyclicCoordinateDescent.Params
import com.typesafe.config.Config
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Random, Try}

/*
 @deprecated Use AdditiveModelTrainer
 */
@Deprecated
object SplineTrainer {
  private final val log: Logger = LoggerFactory.getLogger("SplineTrainer")
    
  case class SplineTrainerParams(
       numBins : Int,
       numBags : Int,
       labelFamily : Family,
       loss : String,
       learningRate : Double,
       dropout : Double,
       subsample : Double,
       margin : Double,
       smoothingTolerance : Double,
       linfinityThreshold : Double,
       threshold : Double,
       lossMod : Int,
       isRanking : Boolean,    // If we have a list based ranking loss
       rankFraction : Double,  // Fraction of time to use ranking loss when loss is rank_and_hinge
       rankMargin : Double,    // The margin for ranking loss
       maxSamplesPerExample : Int, // Max number of samples to use per example
       epsilon : Double,        // epsilon used in epsilon-insensitive loss for regression training
       registry : FeatureRegistry
   )

  def train(sc : SparkContext,
            input : RDD[Example],
            config : Config,
            key : String,
             registry: FeatureRegistry) : SplineModel = {
    val loss : String = config.getString(key + ".loss")
    val isRanking = loss match {
      case "rank_and_hinge" => true
      case "logistic" => false
      case "hinge" => false
      case "regression" => false
      case _ => {
        log.error("Unknown loss function %s".format(loss))
        System.exit(-1)
        false
      }
    }
    val numBins : Int = config.getInt(key + ".num_bins")
    val numBags : Int = config.getInt(key + ".num_bags")
    val iterations : Int = config.getInt(key + ".iterations")
    val labelFamily : Family = registry.family(config.getString(key + ".rank_key"))
    val learningRate : Double = config.getDouble(key + ".learning_rate")
    val dropout : Double = config.getDouble(key + ".dropout")
    val minCount : Int = config.getInt(key + ".min_count")
    val subsample : Double = config.getDouble(key + ".subsample")
    val linfinityCap : Double = config.getDouble(key + ".linfinity_cap")
    val smoothingTolerance : Double = config.getDouble(key + ".smoothing_tolerance")
    val linfinityThreshold : Double = config.getDouble(key + ".linfinity_threshold")
    val initModelPath : String = Try{config.getString(key + ".init_model")}.getOrElse("")
    val threshold : Double = config.getDouble(key + ".rank_threshold")
    val epsilon: Double = Try{config.getDouble(key + ".epsilon")}.getOrElse(0.0)

    val lossMod : Int = try {
      config.getInt(key + ".loss_mod")
    } catch {
      case _ : Throwable => 100
    }

    val margin : Double = Try(config.getDouble(key + ".margin")).getOrElse(1.0)
    
    val multiscale : Array[Int] = Try(
        config.getIntList(key + ".multiscale").asScala.map(x => x.toInt).toArray)
      .getOrElse(Array[Int]())
      
    val rankFraction : Double = Try(config.getDouble(key + ".rank_fraction")).getOrElse(0.5)
    val rankMargin : Double = Try(config.getDouble(key + ".rank_margin")).getOrElse(0.5)
    val maxSamplesPerExample : Int = Try(config.getInt(key + ".max_samples_per_example")).getOrElse(10)
      
    val params = SplineTrainerParams(
       numBins = numBins,
       numBags = numBags,
       labelFamily = labelFamily,
       loss = loss,
       learningRate = learningRate,
       dropout = dropout,
       subsample = subsample,
       margin = margin,
       smoothingTolerance = smoothingTolerance,
       linfinityThreshold = linfinityThreshold,
       threshold = threshold,
       lossMod = lossMod,
       isRanking = isRanking,
       rankFraction = rankFraction,
       rankMargin = rankMargin,
       maxSamplesPerExample = maxSamplesPerExample,
       epsilon = epsilon,
       registry = registry)

    val transformed : RDD[Example] = if (isRanking) {
      LinearRankerUtils.transformExamples(input, config, key, registry)
    } else {
      LinearRankerUtils
        .makePointwiseFloat(input, config, key, registry)
    }

    val initialModel = if(initModelPath == "") {
      None
    } else {
      TrainingUtils.loadScoreModel(initModelPath, registry)
    }
    var model = if(initialModel.isDefined) {
      val newModel = initialModel.get.asInstanceOf[SplineModel]
      newModel.setSplineNormCap(linfinityCap.toFloat)
      initModel(minCount, subsample, labelFamily, transformed, newModel, false)
      newModel
    } else {
      val newModel = new SplineModel(registry)
      newModel.initForTraining(numBins)
      newModel.setSplineNormCap(linfinityCap.toFloat)
      initModel(minCount, subsample, labelFamily, transformed, newModel, true)
      setPrior(config, key, newModel)
      newModel
    }

    log.info("Training using " + loss)
    for (i <- 1 to iterations) {
      model = if (multiscale.size == 0) {
        sgdTrain(sc,     
               config,
               key,
               transformed,
               i,
               params,
               model)
      } else {
        sgdMultiscaleTrain(sc,     
               config,
               key,
               transformed,
               multiscale,
               i,
               params,
               model)
      }
    }
    model
  }

  // Initializes the model
  def initModel(minCount : Int,
                subsample : Double,
                labelFamily : Family,
                input : RDD[Example],
                model : SplineModel,
                overwrite : Boolean) = {
    log.info("Computing min/max values for all features")
    val stats = TrainingUtils
      .getFeatureStatistics(minCount, input.sample(false, subsample))
      .filter(x => x._1.family != labelFamily)
    log.info("Num features = %d".format(stats.length))
    for (entry <- stats) {
      model.addSpline(entry._1, entry._2.min.toFloat, entry._2.max.toFloat, overwrite)
    }
  }

  def setPrior(config : Config,
               key : String,
               model : SplineModel) = {
    try {
      val priors = config.getStringList(key + ".prior")
      for (prior <- priors) {
        val tokens : Array[String] = prior.split(",")
        if (tokens.length == 4) {
          val family = tokens(0)
          val name = tokens(1)
          val start = tokens(2).toDouble
          val end = tokens(3).toDouble
          val feature = model.registry.feature(family, name)
          val weightSpline = model.getWeightSpline.asScala.get(feature)
          if (weightSpline.isDefined) {
            log.info("Setting prior %s:%s <- %f to %f".format(family, name, start, end))
            val len = weightSpline.get.splineWeights.length
            for (i <- 0 until len) {
              val t = i.toDouble / (len.toDouble - 1.0)
              weightSpline.get.splineWeights(i) = ((1.0 - t) * start + t * end).toFloat
            }
          }
        } else {
          log.error("Incorrect number of parameters for %s".format(prior))
        }
      }
    } catch {
      case _ : Throwable => log.info("No prior given")
    }
  }

  def evaluatePolynomial(coeff : Array[Double],
                         data : Array[Double],
                         overwrite : Boolean) : Double = {
    val len = data.length
    var err : Double = 0.0
    var count : Double = 0.0
    for (i <- 0 until len) {
      val t : Double = i.toDouble / (len - 1.0)
      val tinv = 1.0 - t
      val diracStart = if (i == 0) coeff(0) else 0.0
      val diracEnd = if (i == len - 1) coeff(1) else 0.0
      val eval = coeff(2) * tinv * tinv * tinv +
                 coeff(3) * 3.0 * tinv * tinv * t +
                 coeff(4) * 3.0 * tinv * t * t +
                 coeff(5) * t * t * t +
                 diracStart +
                 diracEnd
      if (data(i) != 0.0) {
        err = err + math.abs(eval - data(i))
        count = count + 1.0
      }
      if (overwrite) {
        data(i) = eval
      }
    }
    err / count
  }

  // Fits a polynomial to the data.
  def fitPolynomial(data : Array[Double]) : (Double, Array[Double]) = {
    val numCoeff = 6
    val iterations = numCoeff * 4
    val initial = Array.fill(numCoeff)(0.0)
    val initialStep = Array.fill(numCoeff)(1.0)
    val bounds = Array.fill(numCoeff)((-10.0, 10.0))
    val params = Params(1.0 / 512.0, iterations, initial, initialStep, bounds)
    def f(x : Array[Double]) = {
      evaluatePolynomial(x, data, false)
    }
    val best = CyclicCoordinateDescent.optimize(f, params)
    return (f(best), best)
  }

  // Returns true if we manage to fit a polynomial
  def smoothSpline(tolerance : Double,
                   spline : WeightSpline) = {
    val weights = spline.splineWeights
    val optimize = weights.map(x => x.toDouble).toArray
    val errAndCoeff = fitPolynomial(optimize)
    if (errAndCoeff._1 < tolerance) {
      evaluatePolynomial(errAndCoeff._2, optimize, true)
      for (i <- 0 until weights.length) {
        weights(i) = optimize(i).toFloat
      }
    }
  }

  def sgdTrain(sc : SparkContext,
               config : Config,
               key : String,
               input : RDD[Example],
               iteration : Int,
               params : SplineTrainerParams,
               model : SplineModel) : SplineModel = {
    log.info("Iteration %d".format(iteration))

    val modelBC = sc.broadcast(model)

    input
      .sample(false, params.subsample)
      .coalesce(params.numBags, true)
      .mapPartitions(partition =>
        sgdPartition(partition, modelBC, params))
    .groupByKey()
    // Average the spline weights
    .map(x => {
      val head = x._2.head
      val spline = new WeightSpline(head.spline.getMinVal, head.spline.getMaxVal, params.numBins)
      val scale = 1.0f / params.numBags.toFloat
      x._2.foreach(entry => {
        for (i <- 0 until params.numBins) {
          spline.splineWeights(i) = spline.splineWeights(i) + scale * entry.splineWeights(i)
        }
      })
      smoothSpline(params.smoothingTolerance, spline)
      (x._1, spline)
    })
    .collect()
    .foreach{ case (feature, spline) =>
      model.getWeightSpline.replace(feature, spline)
    }

    deleteSmallSplines(model, params.linfinityThreshold)

    TrainingUtils.saveModel(model, config, key + ".model_output")
    model
  }
  
  def sgdMultiscaleTrain(sc : SparkContext,
                         config : Config,
                         key : String,
                         input : RDD[Example],
                         multiscale : Array[Int],
                         iteration : Int,
                         params : SplineTrainerParams,
                         model : SplineModel) : SplineModel = {
    log.info("Multiscale Iteration %d".format(iteration))

    val modelBC = sc.broadcast(model)

    input
      .sample(false, params.subsample)
      .coalesce(params.numBags, true)
      .mapPartitionsWithIndex((index, partition) =>
        sgdPartitionMultiscale(index, partition, multiscale,
          modelBC, params))
    .groupByKey()
    // Average the spline weights
    .map(x => {
      val head = x._2.head
      val spline = new WeightSpline(head.spline.getMinVal, head.spline.getMaxVal, params.numBins)
      val scale = 1.0f / params.numBags.toFloat
      x._2.foreach(entry => {
        entry.resample(params.numBins)
        for (i <- 0 until params.numBins) {
          spline.splineWeights(i) = spline.splineWeights(i) + scale * entry.splineWeights(i)
        }
      })
      smoothSpline(params.smoothingTolerance, spline)
      (x._1, spline)
    })
    .collect()
    .foreach { case (feature, spline) =>
      model.getWeightSpline.replace(feature, spline)
    }

    deleteSmallSplines(model, params.linfinityThreshold)

    TrainingUtils.saveModel(model, config, key + ".model_output")
    model
  }

  def deleteSmallSplines(model : SplineModel,
                         linfinityThreshold : Double) = {
    val toDelete = mutable.ArrayBuffer[Feature]()

    model.getWeightSpline.asScala.foreach { case (feature, spline) =>
      if (spline.LInfinityNorm < linfinityThreshold) toDelete.append(feature)
    }

    log.info("Deleting %d empty splines".format(toDelete.size))

    toDelete.foreach(feature => model.getWeightSpline.remove(feature))
  }
  
  def sgdPartition(partition : Iterator[Example],
                   modelBC : Broadcast[SplineModel],
                   params : SplineTrainerParams) = {
    sgdPartitionInternal(partition,  modelBC.value, params).iterator
  }
  
  def sgdPartitionMultiscale(
       index : Int,
       partition : Iterator[Example],
       multiscale : Array[Int],
       modelBC : Broadcast[SplineModel],
       params : SplineTrainerParams) = {
    val workingModel = modelBC.value
    
    val newBins = multiscale(index % multiscale.size)
    
    log.info("Resampling to %d bins".format(newBins))
    workingModel
      .getWeightSpline
      .values
      .foreach(_.resample(newBins))

    val output = sgdPartitionInternal(partition, workingModel, params)
    output.iterator
  }
  
  def sgdPartitionInternal(partition : Iterator[Example],
                           workingModel : SplineModel,
                           params : SplineTrainerParams) :
                           mutable.Map[Feature, SplineModel.WeightSpline] = {
    @volatile var lossSum : Double = 0.0
    @volatile var lossCount : Int = 0
    partition.foreach(example => {
      if (params.isRanking) {
        // Since this is SGD we don't want to over sample from one example
        // but we also want to make good use of the example already in RAM
        val count = scala.math.min(params.maxSamplesPerExample, example.size)
        for (i <- 0 until count) {
          lossSum += rankAndHingeLoss(example, workingModel, params)
          lossCount = lossCount + 1 
        }
      } else {
        lossSum += pointwiseLoss(example.only, workingModel, params.loss, params)
        lossCount = lossCount + 1 
      }
      if (lossCount % params.lossMod == 0) {
        log.info("Loss = %f, samples = %d".format(lossSum / params.lossMod.toDouble, lossCount))
        lossSum = 0.0
      }
    })
    workingModel.getWeightSpline
  }
  
  def rankAndHingeLoss(example : Example,
                       workingModel : SplineModel,
                       params : SplineTrainerParams) : Double = {
    val count = example.size
    
    val idx1 = Random.nextInt(count)
    val exampleSeq: Seq[MultiFamilyVector] = example.asScala.toSeq
    val fv1 = exampleSeq(idx1)
    var doHinge : Boolean = false
    var loss : Double = 0.0
    if (Random.nextDouble() < params.rankFraction) {
      val label1 = TrainingUtils.getLabel(fv1, params.labelFamily, params.threshold)
      val idx2 = pickCounterExample(example, idx1, label1, count, params)
      if (idx2 >= 0) {
        val fv2 = exampleSeq(idx2)
        val label2 = TrainingUtils.getLabel(fv2, params.labelFamily, params.threshold)
        // Can't do dropout for ranking loss since we are relying on difference of features.
        val prediction1 = workingModel.scoreItem(fv1)
        val prediction2 = workingModel.scoreItem(fv2)
        if (label1 > label2) {
          loss = scala.math.max(0.0, params.rankMargin - prediction1 + prediction2)
        } else {
          loss = scala.math.max(0.0, params.rankMargin - prediction2 + prediction1)
        }
        
        if (loss > 0) {
          workingModel.update(-label1.toFloat,
                              params.learningRate.toFloat,
                              fv1)
          workingModel.update(-label2.toFloat,
                              params.learningRate.toFloat,
                              fv1)
        }
      } else {
        // No counter example.
        doHinge = true
      }
    } else {
      // We chose to do hinge loss regardless.
      doHinge = true
    }
    if (doHinge) {
      loss = pointwiseLoss(fv1,
                           workingModel,
                           "hinge",
                           params)
    }
    loss
  }
  
  // Picks the first random counter example to idx1
  def pickCounterExample(example : Example,
                         idx1 : Int,
                         label1 : Double,
                         count : Int,
                         params : SplineTrainerParams) : Int = {
    val shuffle = Random.shuffle((0 until count).toBuffer)
    val exampleSeq = example.asScala.toSeq

    for (idx2 <- shuffle) {
      if (idx2 != idx1) {
        val label2 = TrainingUtils.getLabel(
            exampleSeq(idx2), params.labelFamily, params.threshold)
        if (label2 != label1) {
          return idx2
        }
      }
    }
    -1
  }
  
  def pointwiseLoss(fv : MultiFamilyVector,
                    workingModel : SplineModel,
                    loss : String,
                    params : SplineTrainerParams) : Double = {
    val label: Double = if (loss == "regression") {
      TrainingUtils.getLabel(fv, params.labelFamily)
    } else {
      TrainingUtils.getLabel(fv, params.labelFamily, params.threshold)
    }

    loss match {
      case "logistic" => updateLogistic(workingModel, fv, label, params)
      case "hinge" => updateHinge(workingModel, fv, label, params)
      case "regression" => updateRegressor(workingModel, fv, label, params)
    }
  }

  // http://www.cs.toronto.edu/~rsalakhu/papers/srivastava14a.pdf
  // We rescale by 1 / p so that at inference time we don't have to scale by p.
  // In our case p = 1.0 - dropout rate
  def updateLogistic(model : SplineModel,
                     fv : MultiFamilyVector,
                     label : Double,
                     params : SplineTrainerParams) : Double = {
    // TODO (Brad): withFamilyDropout will randomly drop out some DenseVectors but we shouldn't
    // have any.
    val flatFeatures = fv.withFamilyDropout(params.dropout)
    val prediction = model.scoreItem(flatFeatures) / (1.0 - params.dropout)
    // To prevent blowup.
    val corr = scala.math.min(10.0, label * prediction)
    val expCorr = scala.math.exp(corr)
    val loss = scala.math.log(1.0 + 1.0 / expCorr)
    val grad = -label / (1.0 + expCorr)
    model.update(grad.toFloat,
                 params.learningRate.toFloat,
                 flatFeatures)
    return loss
  }

  def updateHinge(model : SplineModel,
                  fv :  MultiFamilyVector,
                  label : Double,
                  params : SplineTrainerParams) : Double = {
    val flatFeatures = fv.withFamilyDropout(params.dropout)
    val prediction = model.scoreItem(flatFeatures) / (1.0 - params.dropout)
    val loss = scala.math.max(0.0, params.margin - label * prediction)
    if (loss > 0.0) {
      val grad = -label
      model.update(grad.toFloat,
                   params.learningRate.toFloat,
                   flatFeatures)
    }
    return loss
  }

  def updateRegressor(model: SplineModel,
                      fv: MultiFamilyVector,
                      label: Double,
                      params : SplineTrainerParams) : Double = {
    val flatFeatures = fv.withFamilyDropout(params.dropout)
    val prediction = model.scoreItem(flatFeatures) / (1.0 - params.dropout)
    val loss = math.abs(prediction - label) // absolute difference
    if (prediction - label > params.epsilon) {
      model.update(1.0f, params.learningRate.toFloat, flatFeatures)
    } else if (prediction - label < -params.epsilon) {
      model.update(-1.0f, params.learningRate.toFloat, flatFeatures)
    }
    return loss
  }

  def trainAndSaveToFile(sc : SparkContext,
                         input : RDD[Example],
                         config : Config,
                         key : String,
                          registry: FeatureRegistry) = {
    val model = train(sc, input, config, key, registry)
    TrainingUtils.saveModel(model, config, key + ".model_output")
  }
}

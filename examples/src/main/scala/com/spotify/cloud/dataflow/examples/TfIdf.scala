package com.spotify.cloud.dataflow.examples

import java.io.File
import java.net.URI

import com.google.cloud.dataflow.sdk.options.GcsOptions
import com.google.cloud.dataflow.sdk.util.gcsfs.GcsPath
import com.spotify.cloud.dataflow._
import com.spotify.cloud.dataflow.values.SCollection

import scala.collection.JavaConverters._

/*
SBT
runMain
  com.spotify.cloud.dataflow.examples.TfIdf
  --project=[PROJECT] --runner=DataflowPipelineRunner --zone=[ZONE]
  --stagingLocation=gs://[BUCKET]/path/to/staging
  --input=gs://dataflow-samples/shakespeare/?*.txt
  --output=gs://[BUCKET]/dataflow/tf_idf
*/

object TfIdf {
  def main(cmdlineArgs: Array[String]): Unit = {
    val (context, args) = ContextAndArgs(cmdlineArgs)

    val uris = {
      val baseUri = new URI(args.getOrElse("input", "gs://dataflow-samples/shakespeare/"))
      val absoluteUri = if (baseUri.getScheme != null) {
        baseUri
      } else {
        new URI("file", baseUri.getAuthority, baseUri.getPath, baseUri.getQuery, baseUri.getFragment)
      }

      if (absoluteUri.getScheme == "file") {
        val dir = new File(absoluteUri)
        dir.list().map(e => new File(dir, e).toURI).toSet
      } else {
        val glob = new URI(
          absoluteUri.getScheme,
          absoluteUri.getAuthority,
          absoluteUri.getPath + "*",
          absoluteUri.getQuery,
          absoluteUri.getFragment)
        context.options.asInstanceOf[GcsOptions].getGcsUtil.expand(GcsPath.fromUri(glob)).asScala.map(_.toUri).toSet
      }
    }

    val docs = SCollection.unionAll(uris.map { uri =>
      val uriString = if (uri.getScheme == "file") new File(uri).getPath else uri.toString
      context.textFile(uriString).keyBy(_ => uriString)
    }.toSeq)

    val uriToWords = docs.flatMap { case (uri, line) =>
      line.split("\\W+").filter(_.nonEmpty).map(w => (uri, w.toLowerCase))
    }  // (d, t)

    val uriToWordAndCount = uriToWords
      .countByValue()  // ((d, t), tf)
      .map(t => (t._1._1, (t._1._2, t._2)))  // (d, (t, tf))


    val wordToDf = uriToWords.distinct().values.countByValue()  // (t, df)
      .withSingletonSideInput(docs.keys.distinct().count())  // N
      .map { case ((t, df), n) => (t, df.toDouble / n) }  // (t, df/N)
      .toSCollection

    uriToWords.keys.countByValue()  // (d, |d|)
      .join(uriToWordAndCount)  // (d, (|d|, (t, tf)))
      .map { case (d, (dl, (t, tf))) => (t, (d, tf.toDouble / dl)) }  // (t, (d, tf/|d|))
      .join(wordToDf)  // (t, ((d, tf/|d|), df/N))
      .map { case (t, ((d, tf), df)) => Seq(t, d, tf * math.log(1 / df)).mkString("\t") }  // (t, d, tf/|d|*log(N/df)
      .saveAsTextFile(args("output"))

    context.close()
  }
}
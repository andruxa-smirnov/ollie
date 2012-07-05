package edu.washington.cs.knowitall.ollie;

import java.io.File
import java.io.PrintWriter
import scala.io.Source
import OllieCli.Settings
import edu.washington.cs.knowitall.common.Resource.using
import edu.washington.cs.knowitall.ollie.confidence.OllieIndependentConfFunction
import edu.washington.cs.knowitall.openparse.OpenParse
import edu.washington.cs.knowitall.tool.parse.StanfordParser
import scopt.OptionParser
import edu.washington.cs.knowitall.tool.sentence.Sentencer
import edu.washington.cs.knowitall.common.Timing

/** An entry point to use Ollie on the command line.
  */
object OllieCli {
  /** A definition of command line arguments.
    */
  abstract class Settings {
    def inputFile: Option[File]
    def outputFile: Option[File]
    def confidenceThreshold: Double

    def splitInput: Boolean
    def tabbed: Boolean
    def parallel: Boolean
    def invincible: Boolean
  }

  /** Size to group for parallelism. */
  private val CHUNK_SIZE = 10000

  def main(args: Array[String]): Unit = {
    object settings extends Settings {
      var inputFile: Option[File] = None
      var outputFile: Option[File] = None
      var confidenceThreshold: Double = 0.0

      var splitInput: Boolean = false
      var tabbed: Boolean = false
      var parallel: Boolean = false
      var invincible: Boolean = false
    }

    // define the argument parser
    val argumentParser = new OptionParser("ollie") {
      argOpt("<input-file>", "pattern file", { path: String =>
        settings.inputFile = Some(new File(path))
      })

      opt(Some("o"), "output", "<output-file>", "output file (otherwise stdout)", { path: String =>
        settings.outputFile = Some(new File(path))
      })

      doubleOpt(Some("t"), "threshold", "<threshold>", "confidence threshold for OpenParse extractor component", { t: Double =>
        settings.confidenceThreshold = t
      })

      opt("p", "parallel", "execute in parallel", { settings.parallel = true })
      opt("tabbed", "output in TSV format", { settings.tabbed = true })
      opt("invincible", "ignore errors", { settings.invincible = true })
    }

    if (argumentParser.parse(args)) {
      run(settings)
    }
  }

  def run(settings: Settings) = {
    System.err.println("Loading models...")
    val parser = new StanfordParser()

    val ollieExtractor = new Ollie(OpenParse.fromModelUrl(OpenParse.defaultModelUrl))
    val confFunction = OllieIndependentConfFunction.loadDefaultClassifier

    val sentencer = None
    
    System.err.println("\nRunning extractor on " + (settings.inputFile match { case None => "standard input" case Some(f) => f.getName }) + "...")
    using(settings.inputFile match {
      case Some(input) => Source.fromFile(input, "UTF-8")
      case None => Source.stdin
    }) { source =>

      using(settings.outputFile match {
        case Some(output) => new PrintWriter(output, "UTF-8")
        case None => new PrintWriter(System.out)
      }) { writer =>

        if (settings.tabbed) writer.println(Iterable("confidence", "arg1", "rel", "arg2", "enabler", "attribution", "dependencies", "text").mkString("\t"))
        val ns = Timing.time {
          val lines = parseLines(source.getLines, sentencer)
          try {
            // group the lines so we can parallelize
            val grouped = if (settings.parallel) lines.grouped(CHUNK_SIZE) else lines.map(Seq(_))
            for (group <- grouped) {
              // potentially transform to a parallel collection
              val sentences = if (settings.parallel) group.par else group
              for (sentence <- sentences) {
                if (!settings.tabbed) {
                  writer.println(sentence)
                  writer.flush()
                }

                // parse the sentence
                val graph = parser.dependencyGraph(sentence)

                // extract sentence and compute confidence
                val extrs = ollieExtractor.extract(graph).map(extr => (confFunction.getConf(extr), extr))
                
                extrs.toSeq.sortBy(-_._1).foreach { case (conf, e) =>
                  if (settings.tabbed) {
                    writer.println(Iterable(conf, e.extr.arg1.text, e.extr.rel.text, e.extr.arg2.text, e.extr.enabler, e.extr.attribution, e.sent.serialize, e.sent.text).mkString("\t"))
                  } else {
                    writer.println(conf + ": " + e.extr)
                  }
                  
                  writer.flush()
                }

                if (!settings.tabbed) { 
                  writer.println()
                  writer.flush()
                }
              }
            }
          } catch {
            case e: Exception if settings.invincible => e.printStackTrace
          }
        }

        System.err.println("completed in " + Timing.Seconds.format(ns) + " seconds")
      }
    }
  }

  def parseLines(linesParam: Iterator[String], sentencer: Option[Sentencer]) = {
    sentencer match {
      case None => linesParam
    }
  }
}

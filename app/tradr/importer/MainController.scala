package tradr.importer


import java.util.concurrent.TimeUnit
import javax.inject.Inject

import akka.actor.ActorSystem
import akka.{Done, NotUsed}
import akka.kafka.ProducerMessage.Message
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream._
import akka.stream.scaladsl.{Keep, RunnableGraph, Sink, Source}
import com.msilb.scalandav20.client.OandaApiClient
import com.msilb.scalandav20.common.Environment.Practice
import com.msilb.scalandav20.model.pricing.PriceStatus.tradeable
import com.msilb.scalandav20.model.pricing.{Price, PricingStreamItem}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import org.apache.kafka.clients.producer.ProducerRecord
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json
import tradr.common.PricingPoint

import scala.concurrent.duration.FiniteDuration

object MainController {


  /**
    * Get a stream that repeats a constant pricing item for debugging purposes
    * @return
    */
  private[this] def getDebuggingPricingStream()(implicit ec: ExecutionContext): Future[Source[PricingStreamItem, Any]] = {
    Future {
      val item = Price(
        `type` = "Price",
        instrument = "EUR_USD",
        time = java.time.Instant.now(),
        status = tradeable,
        bids = Seq(),
        asks = Seq(),
        closeoutBid = 1.0,
        closeoutAsk = 1.0,
        quoteHomeConversionFactors = None,
        unitsAvailable = None
      ).asInstanceOf[PricingStreamItem]

      Source.tick[PricingStreamItem](
        initialDelay = FiniteDuration(1, TimeUnit.SECONDS),
        interval = FiniteDuration(1, TimeUnit.SECONDS),
        tick = item)
    }
  }

  private[this] def getProducerSettings(conf: Config)(implicit system: ActorSystem): ProducerSettings[Array[Byte], String] = {
    val kafkaIp = conf.getString("kafka.ip")
    val kafkaPort =conf.getStringList("kafka.port").get(0)
    val kafkaGroup = conf.getString("kafka.groupId")
    val kafkaBootstrapServer = kafkaIp + ":" + kafkaPort


    ProducerSettings(system, new ByteArraySerializer, new StringSerializer)
      .withBootstrapServers(kafkaBootstrapServer)

  }

  private[this] def getRunnableGraph(source: Future[Source[PricingStreamItem, Any]],
                       producerSettings: ProducerSettings[Array[Byte], String])(implicit ec: ExecutionContext) = {

    source
      .map((pricingSource: Source[PricingStreamItem, Any]) => {
      pricingSource
        .viaMat(KillSwitches.single)(Keep.right)
        .filter(p => p.isInstanceOf[Price])
        .map(p => p.asInstanceOf[Price].closeoutBid.toDouble)
        .map(num => {
          Logger.info(s"EURUSD,${DateTime.now()},$num")
          Json.stringify(
            Json.toJson(PricingPoint(DateTime.now().getMillis, "EURUSD", num))
          )
        })
        .map(num => new ProducerRecord[Array[Byte], String]("EURUSD", num))
        .map(Message(_, NotUsed))
        .via(Producer.flow(producerSettings))
        .toMat(Sink.ignore)(Keep.both)

    })
  }


  def getStream(client: OandaApiClient,
                 conf: Config): Future[RunnableGraph[(UniqueKillSwitch, Future[Done])]] = {

    implicit val system = client.system
    implicit val ec = client.ec

    val accountID = conf.getString("oanda.accountID")
    val producerSettings = getProducerSettings(conf)

    val pricingStream = client.getPricingStream(accountID, Seq("EUR_USD"), None)

    getRunnableGraph(pricingStream, producerSettings)(ec)

  }

  def getDebuggingStream(conf: Config)(implicit ec: ExecutionContext, system: ActorSystem) = {


    val producerSettings = getProducerSettings(conf)
    val pricingDebuggingStream = getDebuggingPricingStream
    getRunnableGraph(pricingDebuggingStream, producerSettings)

  }

}
class MainController @Inject() (implicit ec: ExecutionContext, cc: ControllerComponents)
  extends AbstractController(cc)  {

  private[this] val conf: Config = ConfigFactory.load()

  val oandaAuthToken: String = conf.getString("oanda.authToken")
  val client: OandaApiClient = new OandaApiClient(Practice, oandaAuthToken)

  private[this] var pricingStream: Option[Future[RunnableGraph[(UniqueKillSwitch, Future[Done])]]] = None
  private[this] var killSwitch: Option[UniqueKillSwitch] = None

  /**
    * Start a stream that holds the pricing items from oanda
    * @return
    */
  def startLogging() = Action {
    Future {
    Logger.info("Starting logging")
    if (pricingStream.isEmpty) {
        pricingStream = Some(MainController.getStream(client, conf))
      }
    val res = pricingStream
      .get
      .map(graph => {
        val (switch, startedGraph) = graph.run()(client.materializer)
        killSwitch = Some(switch)
        startedGraph
      })
    }
    Ok("Started Logging")
  }


  /**
    * Stop the current stream
    * @return
    */
  def stopLogging(): Action[AnyContent] = Action {
    Logger.info("Stopping logging")
    if (killSwitch.isDefined) {
      killSwitch.get.shutdown()
      pricingStream = None
      killSwitch = None
      Ok("Stream stopped")
    } else {
      MethodNotAllowed("No killswitch found")
    }
  }

  /**
    * Start a stream with a constant item for debugging purpose
    * @return
    */
  def startDebuggingStream() = Action {
    Logger.info("Starting debugging tream")
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
    implicit val system = ActorSystem()

    pricingStream = Some(MainController.getDebuggingStream(conf)(ec, system))
    pricingStream
      .get
      .map(graph => {
        val (switch, startedGraph) = graph.run()(client.materializer)
        killSwitch = Some(switch)
        startedGraph
      })
    Ok("Debugging stream started")
  }

  def stopDebuggingStream(): Action[AnyContent] = stopLogging()



  }

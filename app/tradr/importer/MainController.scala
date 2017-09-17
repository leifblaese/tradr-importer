package tradr.importer

/**
  * Created by leifblaese on 31.07.17.
  */


import java.io.File
import java.nio.file.Paths
import javax.inject.{Inject, Named}

import akka.{Done, NotUsed}
import akka.actor.{ActorRef, ActorSystem}
import akka.kafka.ProducerMessage.Message
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream._
import akka.stream.scaladsl.{FileIO, Flow, Keep, RunnableGraph, Sink, Source}
import akka.util.ByteString
import com.msilb.scalandav20.client.OandaApiClient
import com.msilb.scalandav20.common.Environment.Practice
import com.msilb.scalandav20.model.pricing.{Price, PricingHeartbeat, PricingStreamItem}
import com.msilb.scalandav20.model.primitives
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{ByteArraySerializer, DoubleSerializer, StringSerializer}

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import org.apache.kafka.clients.producer.ProducerRecord
import org.joda.time.DateTime
import play.api.Logger
import play.api.mvc.Results.Ok
//import scala.concurrent.ExecutionContext
//import scala.concurrent.duration._


object MainController {


  def getTickDataStream(client: OandaApiClient,
                                  conf: Config): Future[RunnableGraph[(UniqueKillSwitch, Future[Done])]] = {



    val accountID = conf.getString("oanda.accountID")
    val kafkaIp = conf.getString("kafka.ip")
    val kafkaPort =conf.getStringList("kafka.port").get(0)
    val kafkaGroup = conf.getString("kafka.groupId")
    val kafkaBootstrapServer = kafkaIp + ":" + kafkaPort


    val producerSettings = ProducerSettings(client.system, new ByteArraySerializer, new StringSerializer)
      .withBootstrapServers(kafkaBootstrapServer)

    val pricingStream = client.getPricingStream(accountID, Seq("EUR_USD"), None)

    pricingStream.map((pricingSource: Source[PricingStreamItem, Any]) => {
      pricingSource
        .viaMat(KillSwitches.single)(Keep.right)
        .filter(p => p.isInstanceOf[Price])
        .map(p => p.asInstanceOf[Price].closeoutBid.toString)
        .map(num => {
          Logger.info(s"EURUSD,${DateTime.now()},$num")
          num
        })
        .map(num => new ProducerRecord[Array[Byte], String]("EURUSD", num))
        .map(Message(_, NotUsed))
        .via(Producer.flow(producerSettings))
        .toMat(Sink.ignore)(Keep.both)

    })(client.ec)

  }


}
class MainController @Inject() (implicit ec: ExecutionContext, cc: ControllerComponents)
  extends AbstractController(cc)  {

  private[this] val conf: Config = ConfigFactory.load()
  private[this] val oandaAuthToken: String = conf.getString("oanda.authToken")
  private[this] val client: OandaApiClient = new OandaApiClient(Practice, oandaAuthToken)

  private[this] var pricingStream: Option[Future[RunnableGraph[(UniqueKillSwitch, Future[Done])]]] = None
  private[this] var killSwitch: Option[UniqueKillSwitch] = None

  def startLogging() = Action {
    Future {
    Logger.info("Starting logging")
    if (pricingStream.isEmpty) {
        pricingStream = Some(MainController.getTickDataStream(client, conf))
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


  def stopLogging() = Action {
    Logger.info("Stopping logging")
    if (killSwitch.isDefined) {
      killSwitch.get.shutdown()
      Ok("Stream stopped")
    } else {
      MethodNotAllowed("No killswitch found")
    }
  }


}

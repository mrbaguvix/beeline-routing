package sg.beeline.ruinrecreate

import com.amazonaws.services.lambda.AWSLambdaAsyncClientBuilder
import com.amazonaws.services.lambda.model.{InvokeRequest, InvokeResult}
import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.jawn.parseByteBuffer
import sg.beeline.io.BuiltIn
import sg.beeline.problem.Request.{RequestFromSuggestion, RequestOverrideTime}
import sg.beeline.problem._
import sg.beeline.ruinrecreate.BeelineSuggestRouteService.{OD, SuggestRouteInput}

import scala.concurrent.{ExecutionContext, Future}

object BeelineSuggestRouteService {
  case class OD(origin: BusStop, destination: BusStop)
  case class SuggestRouteInput(
                                settings: BeelineRecreateSettings,
                                seedRequest: Request,
                                od: OD,
                                requests: List[Request]
                              )
}

object BeelineSuggestRouteSerdes {
  import io.circe.syntax._

  private implicit val (settingsDecoder, settingsEncoder) =
    (deriveDecoder[BeelineRecreateSettings], deriveEncoder[BeelineRecreateSettings])

  // Decoders
  implicit val (busStopDecoder, busStopEncoder) =
    (deriveDecoder[BusStop], deriveEncoder[BusStop])
  implicit val (odDecoder, odEncoder) =
    (deriveDecoder[OD], deriveEncoder[OD])

  implicit val suggestRouteInputDecoder = new Decoder[SuggestRouteInput] {
    override def apply(c: HCursor): Result[SuggestRouteInput] = {
      import io.circe.generic.semiauto._

      for {
        settings <- c.downField("settings").as[BeelineRecreateSettings]
        suggestRouteInput <- {
          val serdes = new SettingsDependentDecoders(settings)
          implicit val requestDecoder = serdes.requestDecoder

          for {
            od <- c.downField("od").as[OD]
            seedRequest <- c.downField("seedRequest").as[Request]
            requests <- c.downField("requests").as[List[Request]]
          } yield SuggestRouteInput(settings, seedRequest, od, requests)
        }
      } yield suggestRouteInput
    }
  }

  /* Encoders */
  implicit val suggestionEncoder = {
    deriveEncoder[Suggestion]
  }
  implicit val requestEncoder: Encoder[Request] = new Encoder[Request] {
    implicit val requestOverrideTimeEncoder = new Encoder[RequestOverrideTime] {
      override def apply(a: RequestOverrideTime): Json = {
        a.r.asJson(requestEncoder)
          .mapObject(o => o.add("time", a.time.asJson))
      }
    }
    implicit val requestFromSuggestionEncoder = new Encoder[RequestFromSuggestion] {
      override def apply(a: RequestFromSuggestion): Json = {
        a.suggestion.asJson
      }
    }

    override def apply(a: Request): Json = a match {
      case a: RequestFromSuggestion => a.asJson
      case a: RequestOverrideTime => a.asJson
      case a: BasicRequest =>
        Suggestion(
          a.id,
          a.start,
          a.end,
          a.time,
          a.weight
        ).asJson
    }
  }

  implicit val activityEncoder = new Encoder[Activity] {
    implicit val pickupEncoder = deriveEncoder[Pickup]
    implicit val dropoffEncoder = deriveEncoder[Dropoff]
    implicit val startActivityEncoder = deriveEncoder[StartActivity]
    implicit val endActivityEncoder = deriveEncoder[EndActivity]

    override def apply(a: Activity): Json = {
      val (activityType, json) = a match {
        case a: Pickup => "pickup" -> pickupEncoder(a)
        case a: Dropoff => "dropoff" -> dropoffEncoder(a)
        case a: StartActivity => "start" -> startActivityEncoder(a)
        case a: EndActivity => "end" -> endActivityEncoder(a)
      }

      json.mapObject(o => o.add("type", Json.fromString(activityType)))
    }
  }

  implicit val routeEncoder = new Encoder[Route] {
    override def apply(a: Route): Json = Json.obj(
      "_activities" -> a.activities.asJson,
      "time" -> a.time.asJson
    )
  }
}

class SettingsDependentDecoders(settings: BeelineRecreateSettings) {
  import BeelineSuggestRouteSerdes._

  val problem = new BasicRoutingProblem(
    List(),
    settings = settings,
    dataSource = BuiltIn
  )

  /* Decoders */
  implicit val suggestionDecoder = deriveDecoder[Suggestion]

  implicit val requestDecoder: Decoder[Request] = new Decoder[Request] {
    override def apply(c: HCursor): Result[Request] =
      for {
        suggestion <- c.as[Suggestion]
      } yield new RequestFromSuggestion(suggestion, problem, BuiltIn)
  }

  implicit val activityDecoder = new Decoder[Activity] {
    implicit val pickupDecoder = deriveDecoder[Pickup]
    implicit val dropoffDecoder = deriveDecoder[Dropoff]
    override def apply(c: HCursor): Result[Activity] = {
      for {
        activityType <- c.downField("type").as[String]

        activity <- activityType match {
          case "pickup" => c.as[Pickup]
          case "dropoff" => c.as[Dropoff]
          case "end" => Right(EndActivity())
          case "start" => Right(StartActivity())
        }
      } yield activity
    }
  }

  implicit val routeDecoder = new Decoder[Route] {
    override def apply(c: HCursor): Result[Route] = {
      for {
        _activities <- c.downField("_activities").as[List[Activity]]
        time <- c.downField("time").as[Double]
      } yield new Route(problem, _activities, time)
    }
  }
  implicit val (settingsDecoder, settingsEncoder) =
    (deriveDecoder[BeelineRecreateSettings], deriveEncoder[BeelineRecreateSettings])
}

trait BeelineSuggestRouteService {
  def executeLambda(
                   settings: BeelineRecreateSettings,
                   request: Request,
                   od: (BusStop, BusStop),
                   requests: List[Request])
                    (implicit executionContext: ExecutionContext): Future[Route] = {
    import io.circe.syntax._
    import BeelineSuggestRouteService._
    import BeelineSuggestRouteSerdes._
    val serdes = new SettingsDependentDecoders(settings)
    import serdes._

    val suggestRouteInput = SuggestRouteInput(settings, request, OD(od._1, od._2), requests)
    implicit val suggestRouteInputEncoder = deriveEncoder[SuggestRouteInput]

    requestWithPayload(suggestRouteInput.asJson.toString)
      .map(json => json.as[Route] match {
        case Right(route) => route
        case Left(exc) => throw exc
      })
  }

  def requestWithPayload(payload: String)
                        (implicit executionContext: ExecutionContext): Future[Json]

}

object AWSLambdaSuggestRouteServiceProxy extends BeelineSuggestRouteService {
  import io.circe.syntax._
  override def requestWithPayload(payload: String)
                                 (implicit executionContext: ExecutionContext): Future[Json] = {

    val lambda = AWSLambdaAsyncClientBuilder.defaultClient()

    val invokeRequest = new InvokeRequest()
      .withFunctionName("beeline-routing-suggestions")
      .withPayload(payload)

    Future {
      val payload = lambda.invokeAsync(invokeRequest).get
      val parsedPayload = parseByteBuffer(payload.getPayload)

      parsedPayload match {
        case Right(r) => r
        case Left(exc) => throw exc
      }
    }
  }
}


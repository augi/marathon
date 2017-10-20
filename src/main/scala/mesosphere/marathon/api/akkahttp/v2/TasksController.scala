package mesosphere.marathon
package api.akkahttp.v2

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.{ Marshaller, ToEntityMarshaller }
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ HttpResponse, MediaRange, MediaTypes, StatusCodes }
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import akka.stream.Materializer
import akka.http.scaladsl.model.MediaTypes.`text/plain`
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.EndpointsHelper
import mesosphere.marathon.api.EndpointsHelper.ListTasks
import mesosphere.marathon.api.akkahttp.Controller
import mesosphere.marathon.api.akkahttp.Directives.pathEndOrSingleSlash
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{ Health, HealthCheckManager }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.{ Authenticator, _ }
import play.api.libs.json.{ JsString, Json }
import mesosphere.marathon.raml.{ AnyToRaml, EnrichedTasksList, Writes }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.state.PathId

import scala.async.Async._
import scala.concurrent.{ ExecutionContext, Future }

class TasksController(
    instanceTracker: InstanceTracker,
    groupManager: GroupManager,
    healthCheckManager: HealthCheckManager,
    val electionService: ElectionService)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val materializer: Materializer
) extends Controller with StrictLogging {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._
  import mesosphere.marathon.raml.EnrichedTaskConversion._

  override val route = {
    asLeader(electionService) {
      authenticated.apply { implicit identity =>
        get {
          (accepts(MediaTypes.`text/plain`) & pathEndOrSingleSlash) {
            listTasksTxt()
          } ~
            (accepts(MediaTypes.`application/json`) & pathEndOrSingleSlash) {
              listTasksJson()
            } ~
            acceptsAnything {
              listTasksJson() // when no accept header present, json is the default choice
            }
        }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def listTasksTxt()(implicit identity: Identity): Route = {
    onSuccess(async {
      val instancesBySpec = await(instanceTracker.instancesBySpec)
      val apps = groupManager.rootGroup().transitiveApps.filterAs(app => authorizer.isAuthorized(identity, ViewRunSpec, app))(collection.breakOut)
      ListTasks(instancesBySpec, apps)
    }) { data =>
      complete(data)
    }
  }

  private def listTasksJson()(implicit identity: Identity): Route = {
    parameters("status".?, "status[]".as[String].*) { (statusParameter, statusParameters) =>
      val statuses = (statusParameter ++ statusParameters).to[Seq]
      onSuccess(enrichedTasks(statuses)) { tasks =>
        complete(TasksList(tasks).toRaml)
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def enrichedTasks(statuses: Seq[String])(implicit identity: Identity): Future[Seq[EnrichedTask]] = async {
    val conditionSet: Set[Condition] = statuses.flatMap(toCondition)(collection.breakOut)

    val instancesBySpec = await(instanceTracker.instancesBySpec)

    val instances: Iterable[(PathId, Instance)] = instancesBySpec.instancesMap.values.flatMap { appTasks =>
      appTasks.instances.map(i => appTasks.specId -> i)
    }
    val appIds: Set[PathId] = instancesBySpec.allSpecIdsWithInstances
    val appIdsToApps = groupManager.apps(appIds)

    val appToPorts: Map[PathId, Seq[Int]] = appIdsToApps.map {
      case (appId, app) => appId -> app.map(_.servicePorts).getOrElse(Nil)
    }

    val instancesHealth = await(
      Source(appIds)
        .mapAsyncUnordered(4)(appId => healthCheckManager.statuses(appId))
        .runFold(Map[Id, Seq[Health]]())(_ ++ _)
    )

    def isInterestingInstance(condition: Condition) = conditionSet.isEmpty || conditionSet(condition)
    def isAuthorized(appId: PathId): Boolean = appIdsToApps(appId).fold(false)(id => authorizer.isAuthorized(identity, ViewRunSpec, id))

    instances
      .filter {
        case (appId, instance) => isAuthorized(appId) && isInterestingInstance(instance.state.condition)
      }
      .flatMap {
        case (appId, instance) => instance.tasksMap.values.map(t => EnrichedTask(
          appId,
          t,
          instance.agentInfo,
          instancesHealth.getOrElse(instance.instanceId, Nil),
          appToPorts.getOrElse(appId, Nil)
        ))
      }.to[Seq]
  }

  implicit val listTasksMarshaller: ToEntityMarshaller[ListTasks] =
    Marshaller
      .stringMarshaller(`text/plain`)
      .compose(data => EndpointsHelper.appsToEndpointString(data))

  private def toCondition(state: String): Option[Condition] = state.toLowerCase match {
    case "running" => Some(Condition.Running)
    case "staging" => Some(Condition.Staging)
    case _ => None
  }

  case class TasksList(tasks: Seq[EnrichedTask])
  implicit val tasksListWrite: Writes[TasksList, EnrichedTasksList] = Writes { tasksList =>
    EnrichedTasksList(tasksList.tasks.map(_.toRaml))
  }
}

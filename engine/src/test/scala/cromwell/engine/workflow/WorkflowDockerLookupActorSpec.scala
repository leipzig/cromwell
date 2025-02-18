package cromwell.engine.workflow

import akka.actor.{ActorRef, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestProbe}
import com.typesafe.config.{Config, ConfigFactory}
import common.util.Backoff
import cromwell.core.actor.StreamIntegration.BackPressure
import cromwell.core.retry.SimpleExponentialBackoff
import cromwell.core.{TestKitSuite, WorkflowId}
import cromwell.database.slick.EngineSlickDatabase
import cromwell.database.sql.tables.DockerHashStoreEntry
import cromwell.docker.DockerInfoActor.{DockerInfoFailedResponse, DockerInfoSuccessResponse, DockerInformation}
import cromwell.docker.{DockerHashResult, DockerImageIdentifier, DockerImageIdentifierWithoutHash, DockerInfoRequest}
import cromwell.engine.workflow.WorkflowDockerLookupActor.{DockerHashActorTimeout, Running, WorkflowDockerLookupFailure, WorkflowDockerTerminalFailure}
import cromwell.engine.workflow.WorkflowDockerLookupActorSpec._
import cromwell.engine.workflow.workflowstore.{StartableState, Submitted}
import cromwell.services.EngineServicesStore
import cromwell.services.ServicesStore._
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.specs2.mock.Mockito

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.control.NoStackTrace


class WorkflowDockerLookupActorSpec
  extends TestKitSuite
    with AnyFlatSpecLike
    with Matchers
    with ImplicitSender
    with BeforeAndAfter
    with Mockito {

  var workflowId: WorkflowId = _
  var dockerSendingActor: TestProbe = _
  var dockerHashingActor: TestProbe = _
  var numReads: Int = _
  var numWrites: Int = _

  before {
    workflowId = WorkflowId.randomId()
    /*
    Instead of TestKit.self use a custom global sender that we reset before each test.

    Otherwise, a latent failure/timeout from one test may be sent to the shared Testkit.self during a different test.
    In that case a call to expectMsg() will suddenly receive an unexpected result. This especially happens in slow
    running CI where the entire suite takes a few minutes to run.
     */
    dockerSendingActor = TestProbe(s"test-sending-probe-$workflowId")
    dockerHashingActor = TestProbe(s"test-hashing-probe-$workflowId")
    numReads = 0
    numWrites = 0
  }

  it should "wait and resubmit the docker request when it gets a backpressure message" in {
    val backoff = SimpleExponentialBackoff(2.seconds, 10.minutes, 2D)

    val lookupActor = TestActorRef(
      Props(new TestWorkflowDockerLookupActor(workflowId, dockerHashingActor.ref, Submitted, backoff)),
      dockerSendingActor.ref,
    )
    lookupActor.tell(LatestRequest, dockerSendingActor.ref)

    dockerHashingActor.expectMsg(LatestRequest)
    dockerHashingActor.reply(BackPressure(LatestRequest))
    // Give a couple of seconds of margin to account for test latency etc...
    dockerHashingActor.expectMsg(2.seconds.+(5 seconds), LatestRequest)
  }

  it should "not look up the same tag again after a successful lookup" in {
    val db = dbWithWrite {
      numWrites = numWrites + 1
      Future.successful(())
    }

    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, isRestart = false, db))
    lookupActor.tell(LatestRequest, dockerSendingActor.ref)

    // The WorkflowDockerLookupActor should not have the hash for this tag yet and will need to query the dockerHashingActor.
    dockerHashingActor.expectMsg(LatestRequest)
    dockerHashingActor.reply(LatestSuccessResponse)
    // The WorkflowDockerLookupActor should forward the success message to this actor.
    dockerSendingActor.expectMsg(LatestSuccessResponse)
    numWrites should equal(1)

    // Now the WorkflowDockerLookupActor should now have this hash in its mappings and should not query the dockerHashingActor again.
    lookupActor.tell(LatestRequest, dockerSendingActor.ref)
    dockerHashingActor.expectNoMessage()
    // The WorkflowDockerLookupActor should forward the success message to this actor.
    dockerSendingActor.expectMsg(LatestSuccessResponse)
    numWrites should equal(1)
  }

  it should "soldier on after docker hashing actor timeouts" in {
    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, isRestart = false))

    lookupActor.tell(LatestRequest, dockerSendingActor.ref)
    lookupActor.tell(OlderRequest, dockerSendingActor.ref)

    val timeout = DockerHashActorTimeout(LatestRequest)

    // The WorkflowDockerLookupActor should not have the hash for this tag yet and will need to query the dockerHashingActor.
    dockerHashingActor.expectMsg(LatestRequest)
    dockerHashingActor.expectMsg(OlderRequest)
    dockerHashingActor.reply(timeout)
    // Send a response for the older request after sending the timeout.  This should cause a mapping to be entered in the
    // WorkflowDockerLookupActor for the older request, which will keep the WorkflowDockerLookupActor from querying the
    // DockerHashActor for this hash again.
    dockerHashingActor.reply(OlderSuccessResponse)

    val results = dockerSendingActor.receiveN(2, 2 seconds).toSet
    val failedRequests = results collect {
      case f: WorkflowDockerLookupFailure if f.request == LatestRequest => f.request
    }

    failedRequests should equal(Set(LatestRequest))

    // Try again.  The hashing actor should receive the latest message and this time won't time out.
    lookupActor.tell(LatestRequest, dockerSendingActor.ref)
    lookupActor.tell(OlderRequest, dockerSendingActor.ref)
    dockerHashingActor.expectMsg(LatestRequest)
    dockerHashingActor.reply(LatestSuccessResponse)

    val responses = dockerSendingActor.receiveN(2, 2 seconds).toSet
    val hashResponses = responses collect { case msg: DockerInfoSuccessResponse => msg }
    // Success after transient timeout failures:
    hashResponses should equal(Set(LatestSuccessResponse, OlderSuccessResponse))
  }

  // BA-6495
  it should "not fail and enter terminal state when response for certain image id from DockerHashingActor arrived after the self-imposed timeout" in {
    val lookupActor = TestFSMRef(new WorkflowDockerLookupActor(workflowId, dockerHashingActor.ref, isRestart = false, EngineServicesStore.engineDatabaseInterface))

    lookupActor.tell(LatestRequest, dockerSendingActor.ref)

    val timeout = DockerHashActorTimeout(LatestRequest)

    // The WorkflowDockerLookupActor should not have the hash for this tag yet and will need to query the dockerHashingActor.
    dockerHashingActor.expectMsg(LatestRequest)
    // WorkflowDockerLookupActor actually sends DockerHashActorTimeout to itself
    lookupActor.tell(timeout, lookupActor)

    val failedRequest: WorkflowDockerLookupFailure = dockerSendingActor.receiveOne(2 seconds).asInstanceOf[WorkflowDockerLookupFailure]
    failedRequest.request shouldBe LatestRequest

    lookupActor.tell(LatestRequest, dockerSendingActor.ref)
    dockerHashingActor.expectMsg(LatestRequest)
    dockerHashingActor.reply(LatestSuccessResponse) // responding for previously timeouted request
    dockerHashingActor.reply(LatestSuccessResponse) // responding for current request

    val hashResponse = dockerSendingActor.receiveOne(2 seconds)
    hashResponse shouldBe LatestSuccessResponse

    // Give WorkflowDockerLookupActor a chance to finish its unfinished business
    Thread.sleep(5000)
    // WorkflowLookup actor should not be in terminal state, since nothing bad happened here
    lookupActor.stateName shouldBe Running
  }

  it should "respond appropriately to docker hash lookup failures" in {
    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, isRestart = false))
    lookupActor.tell(LatestRequest, dockerSendingActor.ref)
    lookupActor.tell(OlderRequest, dockerSendingActor.ref)

    // The WorkflowDockerLookupActor should not have the hash for this tag yet and will need to query the dockerHashingActor.
    dockerHashingActor.expectMsg(LatestRequest)
    dockerHashingActor.expectMsg(OlderRequest)
    val olderFailedResponse = DockerInfoFailedResponse(new RuntimeException("Lookup failed"), OlderRequest)

    dockerHashingActor.reply(LatestSuccessResponse)
    dockerHashingActor.reply(olderFailedResponse)

    val results = dockerSendingActor.receiveN(2, 2 seconds).toSet
    val mixedResponses = results collect {
      case msg: DockerInfoSuccessResponse => msg
      // Scoop out the request here since we can't match the exception on the whole message.
      case msg: WorkflowDockerLookupFailure if msg.reason.getMessage == "Failed to get docker hash for ubuntu:older Lookup failed" => msg.request
    }

    Set(LatestSuccessResponse, OlderRequest) should equal(mixedResponses)

    // Try again, I have a good feeling about this.
    lookupActor.tell(OlderRequest, dockerSendingActor.ref)
    dockerHashingActor.expectMsg(OlderRequest)
    dockerHashingActor.reply(OlderSuccessResponse)
    dockerSendingActor.expectMsg(OlderSuccessResponse)
  }

  it should "reuse previously looked up hashes following a restart" in {
    val db = dbWithQuery {
      Future.successful(
        Seq(LatestStoreEntry(workflowId), OlderStoreEntry(workflowId)))
    }

    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, isRestart = true, db))

    lookupActor.tell(LatestRequest, dockerSendingActor.ref)
    lookupActor.tell(OlderRequest, dockerSendingActor.ref)

    dockerHashingActor.expectNoMessage()

    val results = dockerSendingActor.receiveN(2, 2 seconds).toSet
    val successes = results collect { case result: DockerInfoSuccessResponse => result }

    successes should equal(Set(LatestSuccessResponse, OlderSuccessResponse))
  }

  it should "not try to look up hashes if not restarting" in {
    val db = dbWithWrite(Future.successful(()))
    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, isRestart = false, db))

    lookupActor.tell(LatestRequest, dockerSendingActor.ref)
    lookupActor.tell(OlderRequest, dockerSendingActor.ref)

    dockerHashingActor.expectMsg(LatestRequest)
    dockerHashingActor.expectMsg(OlderRequest)
    dockerHashingActor.reply(LatestSuccessResponse)
    dockerHashingActor.reply(OlderSuccessResponse)

    val results = dockerSendingActor.receiveN(2, 2 seconds).toSet
    val successes = results collect { case result: DockerInfoSuccessResponse => result }

    successes should equal(Set(LatestSuccessResponse, OlderSuccessResponse))
  }

  it should "handle hash write errors appropriately" in {
    val db = dbWithWrite {
      numWrites = numWrites + 1
      if (numWrites == 1) Future.failed(new RuntimeException("Fake exception from a test.")) else Future.successful(())
    }

    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, isRestart = false, db))
    lookupActor.tell(LatestRequest, dockerSendingActor.ref)

    // The WorkflowDockerLookupActor should not have the hash for this tag yet and will need to query the dockerHashingActor.
    dockerHashingActor.expectMsg(LatestRequest)
    dockerHashingActor.reply(LatestSuccessResponse)
    // The WorkflowDockerLookupActor is going to fail when it tries to write to that broken DB.
    dockerSendingActor.expectMsgClass(classOf[WorkflowDockerLookupFailure])
    numWrites should equal(1)

    lookupActor.tell(LatestRequest, dockerSendingActor.ref)
    // The WorkflowDockerLookupActor will query the dockerHashingActor again.
    dockerHashingActor.expectMsg(LatestRequest)
    dockerHashingActor.reply(LatestSuccessResponse)
    // The WorkflowDockerLookupActor should forward the success message to this actor.
    dockerSendingActor.expectMsg(LatestSuccessResponse)
    numWrites should equal(2)
  }

  it should "emit a terminal failure message if failing to read hashes on restart" in {
    val db = dbWithQuery {
      numReads = numReads + 1
      Future.failed(new Exception("Don't worry this is just a dummy failure in a test") with NoStackTrace)
    }

    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, isRestart = true, db))
    lookupActor.tell(LatestRequest, dockerSendingActor.ref)

    dockerHashingActor.expectNoMessage()
    dockerSendingActor.expectMsgClass(classOf[WorkflowDockerTerminalFailure])
    numReads should equal(1)
  }

  it should "emit a terminal failure message if unable to parse hashes read from the database on restart" in {
    val db = dbWithQuery {
      numReads = numReads + 1
      Future.successful(Seq(
        DockerHashStoreEntry(workflowId.toString, Latest, "md5:AAAAA", None),
        // missing the "algorithm:" preceding the hash value so this should fail parsing.
        DockerHashStoreEntry(workflowId.toString, Older, "BBBBB", None)
      ))
    }

    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, isRestart = true, db))
    lookupActor.tell(LatestRequest, dockerSendingActor.ref)

    dockerHashingActor.expectNoMessage()
    dockerSendingActor.expectMsgClass(classOf[WorkflowDockerTerminalFailure])
    numReads should equal(1)
  }

  def dbWithWrite(writeFn: => Future[Unit]): EngineSlickDatabase = {
    databaseInterface(write = _ => writeFn)
  }

  def dbWithQuery(queryFn: => Future[Seq[DockerHashStoreEntry]]): EngineSlickDatabase = {
    databaseInterface(query = _ => queryFn)
  }

  def databaseInterface(query: String => Future[Seq[DockerHashStoreEntry]] = abjectFailure,
                        write: DockerHashStoreEntry => Future[Unit] = abjectFailure): EngineSlickDatabase = {
    new EngineSlickDatabase(DatabaseConfig) {
      override def queryDockerHashStoreEntries(workflowExecutionUuid: String)(implicit ec: ExecutionContext): Future[Seq[DockerHashStoreEntry]] = query(workflowExecutionUuid)

      override def addDockerHashStoreEntry(dockerHashStoreEntry: DockerHashStoreEntry)(implicit ec: ExecutionContext): Future[Unit] = write(dockerHashStoreEntry)
    }.initialized(EngineServicesStore.EngineLiquibaseSettings)
  }
}


object WorkflowDockerLookupActorSpec {
  val Latest = "ubuntu:latest"
  val Older = "ubuntu:older"

  val LatestImageId: DockerImageIdentifierWithoutHash =
    DockerImageIdentifier.fromString(Latest).get.asInstanceOf[DockerImageIdentifierWithoutHash]
  val OlderImageId: DockerImageIdentifierWithoutHash =
    DockerImageIdentifier.fromString(Older).get.asInstanceOf[DockerImageIdentifierWithoutHash]

  val LatestRequest: DockerInfoRequest = DockerInfoRequest(LatestImageId)
  val OlderRequest: DockerInfoRequest = DockerInfoRequest(OlderImageId)

  def LatestStoreEntry(workflowId: WorkflowId): DockerHashStoreEntry = DockerHashStoreEntry(workflowId.toString, Latest, "md5:AAAAAAAA", None)
  def OlderStoreEntry(workflowId: WorkflowId): DockerHashStoreEntry = DockerHashStoreEntry(workflowId.toString, Older, "md5:BBBBBBBB", None)

  val LatestSuccessResponse: DockerInfoSuccessResponse =
    DockerInfoSuccessResponse(DockerInformation(DockerHashResult("md5", "AAAAAAAA"), None), LatestRequest)
  val OlderSuccessResponse: DockerInfoSuccessResponse =
    DockerInfoSuccessResponse(DockerInformation(DockerHashResult("md5", "BBBBBBBB"), None), OlderRequest)

  val DatabaseConfig: Config = ConfigFactory.load.getConfig("database")

  def abjectFailure[A, B]: A => Future[B] = _ => Future.failed(new RuntimeException("Should not be called!"))

  class TestWorkflowDockerLookupActor(workflowId: WorkflowId, dockerHashingActor: ActorRef, startState: StartableState, backoff: Backoff)
    extends WorkflowDockerLookupActor(
      workflowId,
      dockerHashingActor,
      startState.restarted,
      EngineServicesStore.engineDatabaseInterface) {
    override protected def initialBackoff(): Backoff = backoff
  }
}

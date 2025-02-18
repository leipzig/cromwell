package cromwell.cloudsupport.gcp.auth

import java.io.{ByteArrayInputStream, FileNotFoundException, InputStream}
import java.net.HttpURLConnection._
import java.nio.charset.StandardCharsets

import better.files.File
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.{HttpResponseException, HttpTransport}
import com.google.api.client.json.gson.GsonFactory
import com.google.auth.Credentials
import com.google.auth.http.HttpTransportFactory
import com.google.auth.oauth2.{GoogleCredentials, OAuth2Credentials, ServiceAccountCredentials, UserCredentials}
import com.google.cloud.NoCredentials
import com.typesafe.scalalogging.LazyLogging
import cromwell.cloudsupport.gcp.auth.ApplicationDefaultMode.applicationDefaultCredentials
import cromwell.cloudsupport.gcp.auth.GoogleAuthMode._
import cromwell.cloudsupport.gcp.auth.ServiceAccountMode.{CredentialFileFormat, JsonFileFormat, PemFileFormat}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object GoogleAuthMode {

  type OptionLookup = String => String
  val NoOptionLookup: OptionLookup = noOptionLookup

  /**
    * Enables swapping out credential validation for various testing purposes ONLY.
    *
    * All traits in this file are sealed, all classes final, meaning things like Mockito or other java/scala overrides
    * cannot work.
    */
  type CredentialsValidation = Credentials => Unit
  private[auth] val NoCredentialsValidation = mouse.ignore _

  private def noOptionLookup(string: String): Nothing = {
    throw new UnsupportedOperationException(s"cannot lookup $string")
  }

  lazy val jsonFactory: GsonFactory = GsonFactory.getDefaultInstance
  lazy val httpTransport: HttpTransport = GoogleNetHttpTransport.newTrustedTransport
  lazy val HttpTransportFactory: HttpTransportFactory = () => httpTransport

  val UserServiceAccountKey = "user_service_account_json"
  val DockerCredentialsEncryptionKeyNameKey = "docker_credentials_key_name"
  val DockerCredentialsTokenKey = "docker_credentials_token"

  def checkReadable(file: File): Unit = {
    if (!file.isReadable) throw new FileNotFoundException(s"File $file does not exist or is not readable")
  }

  def isFatal(ex: Throwable): Boolean = {
    ex match {
      case http: HttpResponseException =>
        // Using HttpURLConnection fields as com.google.api.client.http.HttpStatusCodes doesn't have Bad Request (400)
        http.getStatusCode == HTTP_UNAUTHORIZED ||
          http.getStatusCode == HTTP_FORBIDDEN ||
          http.getStatusCode == HTTP_BAD_REQUEST
      case _: OptionLookupException => true
      case _ => false
    }
  }

  def extract(options: OptionLookup, key: String): String = {
    Try(options(key)) match {
      case Success(result) => result
      case Failure(throwable) => throw new OptionLookupException(key, throwable)
    }
  }

  /** Used for both checking that the credential is valid and creating a fresh credential. */
  private def refreshCredentials(credentials: Credentials): Unit = {
    credentials.refresh()
  }
}

sealed trait GoogleAuthMode extends LazyLogging {
  def name: String

  /**
    * Creates OAuth credentials with the specified scopes.
    */
  def credentials(options: OptionLookup, scopes: Iterable[String]): OAuth2Credentials

  /**
    * Alias for credentials(GoogleAuthMode.NoOptionLookup, scopes).
    * Only valid for credentials that are NOT externally provided, such as ApplicationDefault.
    */
  def credentials(scopes: Iterable[String]): OAuth2Credentials = {
    credentials(GoogleAuthMode.NoOptionLookup, scopes)
  }

  /**
    * Alias for credentials(GoogleAuthMode.NoOptionLookup, Nil).
    * Only valid for credentials that are NOT externally provided and do not need scopes, such as ApplicationDefault.
    */
  private[auth] def credentials(): OAuth2Credentials = {
    credentials(GoogleAuthMode.NoOptionLookup, Nil)
  }

  /**
    * Alias for credentials(options, Nil).
    * Only valid for credentials that are NOT externally provided and do not need scopes, such as ApplicationDefault.
    */
  private[auth] def credentials(options: OptionLookup): OAuth2Credentials = {
    credentials(options, Nil)
  }

  /**
    * Enables swapping out credential validation for various testing purposes ONLY.
    *
    * All traits in this file are sealed, all classes final, meaning things like Mockito or other java/scala overrides
    * cannot work.
    */
  private[auth] var credentialsValidation: CredentialsValidation = refreshCredentials

  protected def validateCredentials[A <: GoogleCredentials](credential: A,
                                                            scopes: Iterable[String]): GoogleCredentials = {
    val scopedCredentials = credential.createScoped(scopes.asJavaCollection)
    Try(credentialsValidation(scopedCredentials)) match {
      case Failure(ex) => throw new RuntimeException(s"Google credentials are invalid: ${ex.getMessage}", ex)
      case Success(_) => scopedCredentials
    }
  }
}

case class MockAuthMode(override val name: String) extends GoogleAuthMode {
  override def credentials(unusedOptions: OptionLookup, unusedScopes: Iterable[String]): NoCredentials = {
    NoCredentials.getInstance
  }
}

object ServiceAccountMode {

  sealed trait CredentialFileFormat {
    def file: String
  }

  case class PemFileFormat(accountId: String, file: String) extends CredentialFileFormat

  case class JsonFileFormat(file: String) extends CredentialFileFormat

}

final case class ServiceAccountMode(override val name: String,
                                    fileFormat: CredentialFileFormat)
  extends GoogleAuthMode {
  private val credentialsFile = File(fileFormat.file)
  checkReadable(credentialsFile)

  private lazy val serviceAccountCredentials: ServiceAccountCredentials = {
    fileFormat match {
      case PemFileFormat(accountId, _) =>
        logger.warn("The PEM file format will be deprecated in the upcoming Cromwell version. Please use JSON instead.")
        ServiceAccountCredentials.fromPkcs8(accountId, accountId, credentialsFile.contentAsString, null, null)
      case _: JsonFileFormat => ServiceAccountCredentials.fromStream(credentialsFile.newInputStream)
    }
  }

  override def credentials(unusedOptions: OptionLookup,
                           scopes: Iterable[String]): GoogleCredentials = {
    validateCredentials(serviceAccountCredentials, scopes)
  }
}

final case class UserServiceAccountMode(override val name: String) extends GoogleAuthMode {
  private def extractServiceAccount(options: OptionLookup): String = {
    extract(options, UserServiceAccountKey)
  }

  private def credentialStream(options: OptionLookup): InputStream = {
    new ByteArrayInputStream(extractServiceAccount(options).getBytes(StandardCharsets.UTF_8))
  }

  override def credentials(options: OptionLookup, scopes: Iterable[String]): GoogleCredentials = {
    val newCredentials = ServiceAccountCredentials.fromStream(credentialStream(options))
    validateCredentials(newCredentials, scopes)
  }
}


final case class UserMode(override val name: String, secretsPath: String) extends GoogleAuthMode {

  private lazy val secretsStream = {
    val secretsFile = File(secretsPath)
    checkReadable(secretsFile)
    secretsFile.newInputStream
  }

  private lazy val userCredentials: UserCredentials = UserCredentials.fromStream(secretsStream)

  override def credentials(unusedOptions: OptionLookup, scopes: Iterable[String]): GoogleCredentials = {
    validateCredentials(userCredentials, scopes)
  }
}

object ApplicationDefaultMode {
  private lazy val applicationDefaultCredentials: GoogleCredentials = GoogleCredentials.getApplicationDefault
}

final case class ApplicationDefaultMode(name: String) extends GoogleAuthMode {
  override def credentials(unusedOptions: OptionLookup,
                           scopes: Iterable[String]): GoogleCredentials = {
    validateCredentials(applicationDefaultCredentials, scopes)
  }
}

sealed trait ClientSecrets {
  val clientId: String
  val clientSecret: String
}

final case class SimpleClientSecrets(clientId: String, clientSecret: String) extends ClientSecrets

class OptionLookupException(val key: String, cause: Throwable) extends RuntimeException(key, cause)

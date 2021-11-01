package drs.localizer.accesstokens

import com.google.auth.oauth2.GoogleCredentials

import scala.collection.JavaConverters._

/**
 * Strategy for obtaining an access token from Google Application Default credentials that are assumed to already exist.
 */
case object GoogleAccessTokenStrategy extends AccessTokenStrategy {
  private final val UserInfoEmailScope = "https://www.googleapis.com/auth/userinfo.email"
  private final val UserInfoProfileScope = "https://www.googleapis.com/auth/userinfo.profile"
  private final val UserInfoScopes = List(UserInfoEmailScope, UserInfoProfileScope)

  override def getAccessToken(): String = {
    val scopedCredentials = GoogleCredentials.getApplicationDefault().createScoped(UserInfoScopes.asJava)
    scopedCredentials.refreshAccessToken().getTokenValue
  }
}

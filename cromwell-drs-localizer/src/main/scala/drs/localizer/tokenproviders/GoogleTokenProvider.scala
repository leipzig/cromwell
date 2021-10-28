package drs.localizer.tokenproviders

import com.google.auth.oauth2.GoogleCredentials

import scala.collection.JavaConverters._


class GoogleTokenProvider extends AccessTokenProvider {
  private final val UserInfoEmailScope = "https://www.googleapis.com/auth/userinfo.email"
  private final val UserInfoProfileScope = "https://www.googleapis.com/auth/userinfo.profile"
  private final val UserInfoScopes = List(UserInfoEmailScope, UserInfoProfileScope)

  override def getAccessToken(params: Map[String, String]): String = {
    val scopedCredentials = GoogleCredentials.getApplicationDefault().createScoped(UserInfoScopes.asJava)
    scopedCredentials.refreshAccessToken().getTokenValue
  }
}

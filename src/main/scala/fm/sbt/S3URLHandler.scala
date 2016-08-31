/*
 * Copyright 2014 Frugal Mechanic (http://frugalmechanic.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fm.sbt

import java.io.{File, FileInputStream, InputStream}
import java.net.{InetAddress, URI, URL}
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

import com.amazonaws.ClientConfiguration
import com.amazonaws.SDKGlobalConfiguration.{ACCESS_KEY_ENV_VAR, ACCESS_KEY_SYSTEM_PROPERTY, SECRET_KEY_ENV_VAR, SECRET_KEY_SYSTEM_PROPERTY}
import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.{Region, RegionUtils, Regions}
import com.amazonaws.services.s3.model.{AmazonS3Exception, GetObjectRequest, ListObjectsRequest, ObjectListing, ObjectMetadata, PutObjectResult, S3Object}
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3URI}
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient
import com.amazonaws.services.securitytoken.model.{AssumeRoleRequest, AssumeRoleResult}
import org.apache.ivy.util.url.URLHandler
import org.apache.ivy.util.{CopyProgressEvent, CopyProgressListener, Message}

import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.matching.Regex

object S3URLHandler {
  private val DOT_SBT_DIR: File = new File(System.getProperty("user.home"), ".sbt")
  
  // This is for matching region names in URLs or host names
  private val RegionMatcher: Regex = Regions.values().map{ _.getName }.sortBy{ -1 * _.length }.mkString("|").r
  
  private class S3URLInfo(available: Boolean, contentLength: Long, lastModified: Long) extends URLHandler.URLInfo(available, contentLength, lastModified)
  
  private class BucketSpecificSystemPropertiesCredentialsProvider(bucket: String) extends BucketSpecificCredentialsProvider(bucket) {
    
    def AccessKeyName: String = ACCESS_KEY_SYSTEM_PROPERTY
    def SecretKeyName: String = SECRET_KEY_SYSTEM_PROPERTY

    protected def getProp(names: String*): String = names.map{ System.getProperty }.flatMap{ Option(_) }.head.trim
  }
  
  private class BucketSpecificEnvironmentVariableCredentialsProvider(bucket: String) extends BucketSpecificCredentialsProvider(bucket) {
    def AccessKeyName: String = ACCESS_KEY_ENV_VAR
    def SecretKeyName: String = SECRET_KEY_ENV_VAR
    
    protected def getProp(names: String*): String = names.map{ toEnvironmentVariableName }.map{ System.getenv }.flatMap{ Option(_) }.head.trim
  }
  
  private abstract class BucketSpecificCredentialsProvider(bucket: String) extends AWSCredentialsProvider {
    def AccessKeyName: String
    def SecretKeyName: String
    
    def getCredentials(): AWSCredentials = {
      val accessKey: String = getProp(s"${AccessKeyName}.${bucket}", s"${bucket}.${AccessKeyName}")
      val secretKey: String = getProp(s"${SecretKeyName}.${bucket}", s"${bucket}.${SecretKeyName}")
      
      new BasicAWSCredentials(accessKey, secretKey)
    }
    
    def refresh(): Unit = {}
    
    // This should throw an exception if the value is missing
    protected def getProp(names: String*): String
  }

  private abstract class RoleBasedCredentialsProvider(providerChain: AWSCredentialsProviderChain) extends AWSCredentialsProvider {
    def RoleArnKeyNames: Seq[String]

    // This should throw an exception if the value is missing
    protected def getRoleArn(keys: String*): String

    def getCredentials(): AWSCredentials = {
      val securityTokenService: AWSSecurityTokenServiceClient = new AWSSecurityTokenServiceClient(providerChain)

      val roleRequest: AssumeRoleRequest = new AssumeRoleRequest()
        .withRoleArn(getRoleArn(RoleArnKeyNames: _*))
        .withRoleSessionName(System.currentTimeMillis.toString)

      val result: AssumeRoleResult = securityTokenService.assumeRole(roleRequest)

      new BasicSessionCredentials(result.getCredentials.getAccessKeyId, result.getCredentials.getSecretAccessKey, result.getCredentials.getSessionToken)
    }

    def refresh(): Unit = {}
  }

  private class RoleBasedSystemPropertiesCredentialsProvider(providerChain: AWSCredentialsProviderChain)
      extends RoleBasedCredentialsProvider(providerChain) {

    val RoleArnKeyName: String = "aws.roleArn"
    val RoleArnKeyNames: Seq[String] = Seq(RoleArnKeyName)

    protected def getRoleArn(keys: String*) = keys.map( System.getProperty ).flatMap( Option(_) ).head.trim
  }

  private class RoleBasedEnvironmentVariableCredentialsProvider(providerChain: AWSCredentialsProviderChain)
      extends RoleBasedCredentialsProvider(providerChain) {

    val RoleArnKeyName: String = "AWS_ROLE_ARN"
    val RoleArnKeyNames: Seq[String] = Seq("AWS_ROLE_ARN")

    protected def getRoleArn(keys: String*) = keys.map( toEnvironmentVariableName ).map( System.getenv ).flatMap( Option(_) ).head.trim
  }

  private class RoleBasedPropertiesFileCredentialsProvider(providerChain: AWSCredentialsProviderChain, fileName: String)
      extends RoleBasedCredentialsProvider(providerChain) {

    val RoleArnKeyName: String = "roleArn"
    val RoleArnKeyNames: Seq[String] = Seq(RoleArnKeyName)

    protected def getRoleArn(keys: String*): String = {
      val file: File = new File(DOT_SBT_DIR, fileName)
      
      // This will throw if the file doesn't exist
      val is: InputStream = new FileInputStream(file)
      
      try {
        val props: Properties = new Properties()
        props.load(is)
        // This will throw if there is no matching properties
        RoleArnKeyNames.map{ props.getProperty }.flatMap{ Option(_) }.head.trim
      } finally is.close()
    }
  }

  private class BucketSpecificRoleBasedSystemPropertiesCredentialsProvider(providerChain: AWSCredentialsProviderChain, bucket: String)
      extends RoleBasedSystemPropertiesCredentialsProvider(providerChain) {

    override val RoleArnKeyNames: Seq[String] = Seq(s"${RoleArnKeyName}.${bucket}", s"${bucket}.${RoleArnKeyName}")
  }

  private class BucketSpecificRoleBasedEnvironmentVariableCredentialsProvider(providerChain: AWSCredentialsProviderChain, bucket: String)
      extends RoleBasedEnvironmentVariableCredentialsProvider(providerChain) {

    override val RoleArnKeyNames: Seq[String] = Seq(s"${RoleArnKeyName}.${bucket}", s"${bucket}.${RoleArnKeyName}")
  }
  
  private def toEnvironmentVariableName(s: String): String = s.toUpperCase.replace('-','_').replace('.','_').replaceAll("[^A-Z0-9_]", "")
}

/**
 * This implements the Ivy URLHandler
 */
final class S3URLHandler extends URLHandler {
  import fm.sbt.S3URLHandler._
  import org.apache.ivy.util.url.URLHandler.{UNAVAILABLE, URLInfo}
  
  def isReachable(url: URL): Boolean = getURLInfo(url).isReachable
  def isReachable(url: URL, timeout: Int): Boolean = getURLInfo(url, timeout).isReachable
  def getContentLength(url: URL): Long = getURLInfo(url).getContentLength
  def getContentLength(url: URL, timeout: Int): Long = getURLInfo(url, timeout).getContentLength
  def getLastModified(url: URL): Long = getURLInfo(url).getLastModified
  def getLastModified(url: URL, timeout: Int): Long = getURLInfo(url, timeout).getLastModified
  def getURLInfo(url: URL): URLInfo = getURLInfo(url, 0)
  
  private def debug(msg: String): Unit = Message.debug("S3URLHandler."+msg)
  
  private def makePropertiesFileCredentialsProvider(fileName: String): PropertiesFileCredentialsProvider = {
    val file: File = new File(DOT_SBT_DIR, fileName)
    new PropertiesFileCredentialsProvider(file.toString)
  }
  
  private def makeCredentialsProviderChain(bucket: String): AWSCredentialsProviderChain = {
    val basicProviders: Vector[AWSCredentialsProvider] = Vector(
      new BucketSpecificEnvironmentVariableCredentialsProvider(bucket),
      new BucketSpecificSystemPropertiesCredentialsProvider(bucket),
      makePropertiesFileCredentialsProvider(s".s3credentials_${bucket}"),
      makePropertiesFileCredentialsProvider(s".${bucket}_s3credentials"),
      new EnvironmentVariableCredentialsProvider(),
      new SystemPropertiesCredentialsProvider(),
      new ProfileCredentialsProvider(),
      makePropertiesFileCredentialsProvider(".s3credentials"),
      new InstanceProfileCredentialsProvider()
    )

    val basicProviderChain: AWSCredentialsProviderChain = new AWSCredentialsProviderChain(basicProviders: _*)

    val roleBasedProviders: Vector[AWSCredentialsProvider] = Vector(
      new BucketSpecificRoleBasedEnvironmentVariableCredentialsProvider(basicProviderChain, bucket),
      new BucketSpecificRoleBasedSystemPropertiesCredentialsProvider(basicProviderChain, bucket),
      new RoleBasedPropertiesFileCredentialsProvider(basicProviderChain, s".s3credentials_${bucket}"),
      new RoleBasedPropertiesFileCredentialsProvider(basicProviderChain, s".${bucket}_s3credentials"),
      new RoleBasedEnvironmentVariableCredentialsProvider(basicProviderChain),
      new RoleBasedSystemPropertiesCredentialsProvider(basicProviderChain),
      new RoleBasedPropertiesFileCredentialsProvider(basicProviderChain, s".s3credentials")
    )
    
    new AWSCredentialsProviderChain((roleBasedProviders union basicProviders): _*)
  }
  
  private val credentialsCache: ConcurrentHashMap[String,AWSCredentials] = new ConcurrentHashMap()

  def getCredentials(bucket: String): AWSCredentials = {
    val chain = new DefaultAWSCredentialsProviderChain()
    val credentials = chain.getCredentials
    credentials
  }

  def getProxyConfiguration: ClientConfiguration = {
    val configuration = new ClientConfiguration()
    for {
      proxyHost <- Option( System.getProperty("https.proxyHost") )
      proxyPort <- Option( System.getProperty("https.proxyPort").toInt )
    } {
      configuration.setProxyHost(proxyHost)
      configuration.setProxyPort(proxyPort)
    }
    configuration
  }
  
  def getClientBucketAndKey(url: URL): (AmazonS3Client, String, String) = {
    val (bucket, key) = getBucketAndKey(url)
    val client: AmazonS3Client = new AmazonS3Client(getCredentials(bucket), getProxyConfiguration)
    
    val region: Option[Region] = getRegion(url, bucket, client)
    region.foreach{ client.setRegion }
    
    (client, bucket, key)
  }
  
  def getURLInfo(url: URL, timeout: Int): URLInfo = try {
    debug(s"getURLInfo($url, $timeout)")
    
    val (client, bucket, key) = getClientBucketAndKey(url)
    
    val meta: ObjectMetadata = client.getObjectMetadata(bucket, key)
    
    val available: Boolean = true
    val contentLength: Long = meta.getContentLength
    val lastModified: Long = meta.getLastModified.getTime
    
    new S3URLInfo(available, contentLength, lastModified)
  } catch {
    case ex: AmazonS3Exception if ex.getStatusCode == 404 => UNAVAILABLE
  }
  
  def openStream(url: URL): InputStream = {
    debug(s"openStream($url)")
    
    val (client, bucket, key) = getClientBucketAndKey(url)
    val obj: S3Object = client.getObject(bucket, key)
    obj.getObjectContent()
  }
  
  /**
   * A directory listing for keys/directories under this prefix
   */
  def list(url: URL): Seq[URL] = {
    debug(s"list($url)")
    
    val (client, bucket, key /* key is the prefix in this case */) = getClientBucketAndKey(url)
    
    // We want the prefix to have a trailing slash
    val prefix: String = key.stripSuffix("/") + "/"
    
    val request: ListObjectsRequest = new ListObjectsRequest().withBucketName(bucket).withPrefix(prefix).withDelimiter("/")
    
    val listing: ObjectListing = client.listObjects(request)
    
    require(!listing.isTruncated, "Truncated ObjectListing!  Making additional calls currently isn't implemented!")
    
    val keys: Seq[String] = listing.getCommonPrefixes.asScala ++ listing.getObjectSummaries.asScala.map{ _.getKey }
    
    val res: Seq[URL] = keys.map{ k: String =>
      new URL(url.toString.stripSuffix("/") + "/" + k.stripPrefix(prefix))
    }
    
    debug(s"list($url) => \n  "+res.mkString("\n  "))
    
    res
  }
  
  def download(src: URL, dest: File, l: CopyProgressListener): Unit = {
    debug(s"download($src, $dest)")
    
    val (client, bucket, key) = getClientBucketAndKey(src)
    
    val event: CopyProgressEvent = new CopyProgressEvent()
    if (null != l) l.start(event)
    
    val meta: ObjectMetadata = client.getObject(new GetObjectRequest(bucket, key), dest)
    dest.setLastModified(meta.getLastModified.getTime)
    
    if (null != l) l.end(event) //l.progress(evt.update(EMPTY_BUFFER, 0, meta.getContentLength))
  }
  
  def upload(src: File, dest: URL, l: CopyProgressListener): Unit = {
    debug(s"upload($src, $dest)")
    
    val event: CopyProgressEvent = new CopyProgressEvent()
    if (null != l) l.start(event)
    
    val (client, bucket, key) = getClientBucketAndKey(dest)
    val res: PutObjectResult = client.putObject(bucket, key, src)
    
    if (null != l) l.end(event)
  }
  
  // I don't think we care what this is set to
  def setRequestMethod(requestMethod: Int): Unit = debug(s"setRequestMethod($requestMethod)")
  
  // Try to get the region of the S3 URL so we can set it on the S3Client
  def getRegion(url: URL, bucket: String, client: AmazonS3Client): Option[Region] = {
    val region: Option[String] = getRegionNameFromURL(url) orElse getRegionNameFromDNS(bucket) orElse getRegionNameFromService(bucket, client)

    region.map{ RegionUtils.getRegion }.flatMap{ Option(_) }
  }
  
  def getRegionNameFromURL(url: URL): Option[String] = {
    // We'll try the AmazonS3URI parsing first then fallback to our RegionMatcher
    getAmazonS3URI(url).map{ _.getRegion }.flatMap{ Option(_) } orElse RegionMatcher.findFirstIn(url.toString)
  }
  
  def getRegionNameFromDNS(bucket: String): Option[String] = {
    // This gives us something like s3-us-west-2-w.amazonaws.com which must have changed
    // at some point because the region from that hostname is no longer parsed by AmazonS3URI
    val canonicalHostName: String = InetAddress.getByName(bucket+".s3.amazonaws.com").getCanonicalHostName()
    
    // So we use our regex based RegionMatcher to try and extract the region since AmazonS3URI doesn't work
    RegionMatcher.findFirstIn(canonicalHostName)
  }
  
  // TODO: cache the result of this so we aren't always making the call
  def getRegionNameFromService(bucket: String, client: AmazonS3Client): Option[String] = {
    // This might fail if the current credentials don't have access to the getBucketLocation call
    Try { client.getBucketLocation(bucket) }.toOption
  }
  
  def getBucketAndKey(url: URL): (String, String) = {
    // The AmazonS3URI constructor should work for standard S3 urls.  But if a custom domain is being used
    // (e.g. snapshots.maven.frugalmechanic.com) then we treat the hostname as the bucket and the path as the key
    getAmazonS3URI(url).map{ amzn: AmazonS3URI =>
      (amzn.getBucket, amzn.getKey)
    }.getOrElse {
      // Probably a custom domain name - The host should be the bucket and the path the key
      (url.getHost, url.getPath.stripPrefix("/"))
    }
  }
  
  def getAmazonS3URI(uri: String): Option[AmazonS3URI] = getAmazonS3URI(URI.create(uri))
  def getAmazonS3URI(url: URL)   : Option[AmazonS3URI] = getAmazonS3URI(url.toURI)
  
  def getAmazonS3URI(uri: URI)   : Option[AmazonS3URI] = try {
    val httpsURI: URI =
      // If there is no scheme (e.g. new URI("s3-us-west-2.amazonaws.com/<bucket>"))
      // then we need to re-create the URI to add one and to also make sure the host is set
      if (uri.getScheme == null) new URI("https://"+uri)
      // AmazonS3URI can't parse the region from s3:// URLs so we rewrite the scheme to https://
      else new URI("https", uri.getUserInfo, uri.getHost, uri.getPort, uri.getPath, uri.getQuery, uri.getFragment)

    Some(new AmazonS3URI(httpsURI))
  } catch {
    case _: IllegalArgumentException => None
  }
}
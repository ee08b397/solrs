package io.ino.solrs

import java.util.Arrays.asList

import akka.actor.ActorSystem
import org.asynchttpclient.DefaultAsyncHttpClient
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrQuery.SortClause
import org.apache.solr.client.solrj.impl.XMLResponseParser
import org.apache.solr.client.solrj.response.QueryResponse
import org.mockito.Matchers._
import org.mockito.Mockito.verify
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class AsyncSolrClientIntegrationSpec extends StandardFunSpec with RunningSolr {

  private implicit val timeout = 1.second
  private val httpClient = new DefaultAsyncHttpClient()

  private lazy val solrUrl = s"http://localhost:${solrRunner.port}/solr/collection1"
  private lazy val solrs = AsyncSolrClient(solrUrl)

  import io.ino.solrs.SolrUtils._

  override def beforeEach() {
    eventually(Timeout(10 seconds)) {
      solr.deleteByQuery("*:*")
    }
    val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
    val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
    solr.add(asList(doc1, doc2))
    solr.commit()
  }

  override def afterAll(): Unit = {
    solrs.shutdown()
    httpClient.close()
  }

  describe("Solr") {

    it("should query async with SolrQuery") {

      val query = new SolrQuery()
      query.setQuery("cat:cat1")
      query.addSort(SortClause.asc("price"))

      val response: Future[QueryResponse] = solrs.query(query)

      val docs = await(response).getResults
      docs.getNumFound should be (2)
      docs.size should be (2)
      docs.get(0).getFieldValue("price") should be (10f)
      docs.get(1).getFieldValue("price") should be (20f)
    }

    it("should allow to transform the response") {
      val response: Future[List[String]] = solrs.query(new SolrQuery("cat:cat1")).map(getIds)

      await(response) should contain theSameElementsAs Vector("id1", "id2")
    }

    it("should allow to regularly observe the server status") {
      val solrServers = Seq(SolrServer(solrUrl))

      val solr = AsyncSolrClient.Builder(new SingleServerLB(solrUrl)).withServerStateObservation(
        new PingStatusObserver(solrServers, httpClient),
        20 millis,
        ActorSystem("test-actorsystem")
      ).build

      enable(solrUrl)
      eventually {
        solrServers(0).status should be (Enabled)
      }

      disable(solrUrl)
      eventually {
        solrServers(0).status should be (Disabled)
      }

      solr.shutdown()
    }

    it("should be built with LoadBalancer") {
      val solr = AsyncSolrClient.Builder(new SingleServerLB(solrUrl)).build
      val response = solr.query(new SolrQuery("cat:cat1"))
      await(response).getResults.getNumFound should be (2)
    }

    it("should allow to set the http client") {

      val solr = AsyncSolrClient.Builder(solrUrl).withHttpClient(new DefaultAsyncHttpClient()).build

      val response = solr.query(new SolrQuery("cat:cat1"))

      await(response).getResults.getNumFound should be (2)

      solr.shutdown()
    }

    it("should allow to set the response parser") {

      val solr = AsyncSolrClient.Builder(solrUrl).withResponseParser(new XMLResponseParser()).build

      val response = solr.query(new SolrQuery("cat:cat1"))

      await(response).getResults.getNumFound should be (2)

      solr.shutdown()
    }

    it("should return failed future on request with bad query") {

      val response: Future[QueryResponse] = solrs.query(new SolrQuery("fieldDoesNotExist:foo"))

      awaitReady(response)
      a [RemoteSolrException] should be thrownBy await(response)
      (the [RemoteSolrException] thrownBy await(response)).getMessage should include ("undefined field fieldDoesNotExist")
    }

    it("should return failed future on wrong request path") {
      val solr = AsyncSolrClient(s"http://localhost:${solrRunner.port}/")

      val response = solr.query(new SolrQuery("*:*"))

      awaitReady(response)
      a [RemoteSolrException] should be thrownBy await(response)
      (the [RemoteSolrException] thrownBy await(response)).getMessage should include ("parsing error")

      solr.shutdown()
    }

    it("should gather request time metrics") {
      val metrics = mock[Metrics]
      val solr = AsyncSolrClient.Builder(solrUrl).withMetrics(metrics).build

      await(solr.query(new SolrQuery("*:*")))

      verify(metrics).requestTime(anyLong())

      solr.shutdown()
    }

    it("should allow to intercept requests") {
      var capturedServer: SolrServer = null
      var capturedQuery: SolrQuery = null
      val interceptor = new RequestInterceptor {
        override def interceptQuery(f: (SolrServer, SolrQuery) => future.Future[QueryResponse])
                                   (solrServer: SolrServer, q: SolrQuery): future.Future[QueryResponse] = {
          capturedServer = solrServer
          capturedQuery = q
          f(solrServer, q)
        }
      }
      val solr = AsyncSolrClient.Builder(solrUrl).withRequestInterceptor(interceptor).build

      val q: SolrQuery = new SolrQuery("*:*")
      await(solr.query(q))

      capturedServer should be (SolrServer(solrUrl))
      capturedQuery should be (q)

      solr.shutdown()
    }

  }

  private def enable(solrUrl: String) = setStatus(solrUrl, "enable")
  private def disable(solrUrl: String) = setStatus(solrUrl, "disable")
  private def setStatus(solrUrl: String, action: String) =
    httpClient.prepareGet(s"$solrUrl/admin/ping?action=$action").execute().get()

}

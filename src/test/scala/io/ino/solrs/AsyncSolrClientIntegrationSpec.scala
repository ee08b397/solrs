package io.ino.solrs

import akka.actor.ActorSystem
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, BeforeAndAfterEach, FunSpec}
import org.mockito.Mockito.verify
import org.mockito.Matchers.anyLong
import org.apache.solr.common.SolrInputDocument
import java.util.Arrays.asList
import org.apache.solr.client.solrj.response.QueryResponse
import com.ning.http.client.AsyncHttpClient
import org.apache.solr.client.solrj.SolrQuery.SortClause
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.XMLResponseParser
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import org.scalatest.concurrent.Eventually._

/**
 * Created by magro on 4/27/14.
 */
class AsyncSolrClientIntegrationSpec extends FunSpec with RunningSolr with BeforeAndAfterEach with Matchers with FutureAwaits with MockitoSugar {

  private implicit val timeout = 1.second
  private val httpClient = new AsyncHttpClient()

  override def beforeEach() {
    solr.deleteByQuery("*:*")
    val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
    val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
    solr.add(asList(doc1, doc2))
    solr.commit()
  }

  private def newInputDoc(id: String, name: String, category: String, price: Float): SolrInputDocument = {
    val doc = new SolrInputDocument()
    doc.addField("id", id)
    doc.addField("name", name)
    doc.addField("cat", category)
    doc.addField("price", price)
    doc
  }

  private def getIds(resp: QueryResponse): List[String] = {
    import scala.collection.JavaConversions._
    resp.getResults.toList.map(_.getFieldValue("id").toString)
  }

  describe("Solr") {

    lazy val solrUrl = s"http://localhost:${solrRunner.port}/solr"
    lazy val solr = AsyncSolrClient(solrUrl)

    it("should query async with SolrQuery") {

      val query = new SolrQuery()
      query.setQuery("cat:cat1")
      query.addSort(SortClause.asc("price"))

      val response: Future[QueryResponse] = solr.query(query)

      val docs = await(response).getResults
      docs.getNumFound should be (2)
      docs.size should be (2)
      docs.get(0).getFieldValue("price") should be (10f)
      docs.get(1).getFieldValue("price") should be (20f)
    }

    it("should allow to transform the response") {
      val response: Future[List[String]] = solr.query(new SolrQuery("cat:cat1"), getIds)

      await(response) should contain theSameElementsAs Vector("id1", "id2")
    }

    it("should allow to regularly observe the server status") {
      implicit val ec = ExecutionContext.Implicits.global
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

      solr.shutdown
    }

    it("should be built with LoadBalancer") {
      val solr = AsyncSolrClient.Builder(new SingleServerLB(solrUrl)).build
      val response = solr.query(new SolrQuery("cat:cat1"))
      await(response).getResults.getNumFound should be (2)
    }

    it("should allow to set the http client") {

      val solr = AsyncSolrClient.Builder(solrUrl).withHttpClient(new AsyncHttpClient()).build

      val response = solr.query(new SolrQuery("cat:cat1"))

      await(response).getResults.getNumFound should be (2)
    }

    it("should allow to set the response parser") {

      val solr = AsyncSolrClient.Builder(solrUrl).withResponseParser(new XMLResponseParser()).build

      val response = solr.query(new SolrQuery("cat:cat1"))

      await(response).getResults.getNumFound should be (2)
    }

    it("should return failed future on request with bad query") {

      val response: Future[QueryResponse] = solr.query(new SolrQuery("fieldDoesNotExist:foo"))

      awaitReady(response)
      a [RemoteSolrException] should be thrownBy await(response)
    }

    it("should return failed future on wrong request path") {
      val solr = AsyncSolrClient(s"http://localhost:${solrRunner.port}/")

      val response = solr.query(new SolrQuery("*:*"), getIds)

      awaitReady(response)
      a [RemoteSolrException] should be thrownBy await(response)
    }

    it("should gather request time metrics") {
      val metrics = mock[Metrics]
      val solr = AsyncSolrClient.Builder(solrUrl).withMetrics(metrics).build

      await(solr.query(new SolrQuery("*:*")))

      verify(metrics).requestTime(anyLong())
    }

  }

  private def enable(solrUrl: String) = setStatus(solrUrl, "enable")
  private def disable(solrUrl: String) = setStatus(solrUrl, "disable")
  private def setStatus(solrUrl: String, action: String) =
    httpClient.prepareGet(s"$solrUrl/admin/ping?action=$action").execute().get()

}

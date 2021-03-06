package io.ino.solrs

import io.ino.solrs.future.ScalaFutureFactory.ScalaFuture
import io.ino.time.Clock.MutableClock
import org.apache.solr.client.solrj.response.QueryResponse
import org.mockito.Matchers._
import org.mockito.Mockito._

import scala.concurrent._
import scala.concurrent.duration._
import scala.language.{higherKinds, postfixOps}
import scala.util.{Success, Try}

object AsyncSolrClientMocks {

  def mockDoQuery[F[_]](mock: AsyncSolrClient[F],
                        solrServer: => SolrServer = any[SolrServer](),
                        responseDelay: Duration = 1 milli)
                       (implicit clock: MutableClock): AsyncSolrClient[F] = {
    // for spies doReturn should be used...
    doReturn(delayedResponse(responseDelay.toMillis)).when(mock).doQuery(solrServer, any())
    mock
  }

  def mockDoQuery[F[_]](mock: AsyncSolrClient[F],
                        futureResponse: future.Future[QueryResponse]): AsyncSolrClient[F] = {
    // for spies doReturn should be used...
    doReturn(futureResponse).when(mock).doQuery(any[SolrServer](), any())
    mock
  }

  def   delayedResponse(delay: Long)(implicit clock: MutableClock): future.Future[QueryResponse] = {
    val response = new QueryResponse()
    new ScalaFuture(new Future[QueryResponse] {
      override def onComplete[U](func: (Try[QueryResponse]) => U)(implicit executor: ExecutionContext): Unit = {
        clock.advance(delay)
        func(Success(response))
      }

      override def isCompleted: Boolean = true

      override def value: Option[Try[QueryResponse]] = Some(Success(response))

      @throws(classOf[Exception])
      override def result(atMost: Duration)(implicit permit: CanAwait): QueryResponse = response

      @throws(classOf[InterruptedException])
      @throws(classOf[TimeoutException])
      override def ready(atMost: Duration)(implicit permit: CanAwait): this.type = this
    })
  }

}

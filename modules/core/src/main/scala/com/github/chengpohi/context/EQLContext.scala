package com.github.chengpohi.context

import com.github.chengpohi.dsl.EQLClient
import com.github.chengpohi.dsl.eql._

trait EQLContext
  extends AggsEQL
    with AnalyzeEQL
    with DeleterEQL
    with IndexEQL
    with ManageEQL
    with QueryEQL {
  this: EQLConfig =>
  val ALL_INDEX: String = "*"
  val ALL_TYPE: String = "_all"
  override implicit lazy val eqlClient: EQLClient = buildClient(config)

  def shutdown: Unit = {
    eqlClient.client.close()
    eqlClient.restClient.close()
  }
}
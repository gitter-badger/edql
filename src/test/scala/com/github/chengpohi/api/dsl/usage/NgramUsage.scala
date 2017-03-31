package com.github.chengpohi.api.dsl.usage

import com.github.chengpohi.helper.ELKCommandTestRegistry

import scala.io.Source

object NgramUsage extends App {
  val dsl = ELKCommandTestRegistry.elasticdsl
  val corpus = Source
    .fromInputStream(this.getClass.getResourceAsStream("/training/corpus.txt"))
    .getLines()
    .zipWithIndex
    .map(s => Map("text" -> s._1, "id" -> s._2)).toList

  import dsl._

  val _indexType = "foo"
  val _indexName = "bar"
  DSL {
    create index _indexName tokenizer List(
      create tokenizer "my_tokenizer" tpe "ngram" min_gram 3 max_gram 3 token_chars List("letter", "digit")
    ) analyzers List(
      create analyze "my_analyzer" tokenizer "my_tokenizer"
    ) fields List(
      create field "text" in _indexType tpe "text" store true analyzer "my_analyzer"
    ) settings (IndexSettingsDefinition() number_of_replicas 0 number_of_shards 1)
  }.await

  val response = DSL {
    analyze text "Quick Fox" tokenizer "ngram"
  }.toJson

  println(response)
}

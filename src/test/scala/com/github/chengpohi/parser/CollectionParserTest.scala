package com.github.chengpohi.parser

import fastparse.core.Parsed
import org.scalatest.{FlatSpec, BeforeAndAfter}

import scala.collection.mutable.ArrayBuffer

/**
 * elasticshell
 * Created by chengpohi on 2/1/16.
 */
class CollectionParserTest extends FlatSpec with BeforeAndAfter {
  val collectionParser = new CollectionParser

  import collectionParser._

  "collection" should "parse tuple" in {
    val Parsed.Success(value1, _) = collection.parse("(1,2,3)")
    assert(value1 === ArrayBuffer(1, 2, 3))
  }

  "collection" should "parse array" in {
    val Parsed.Success(value, _) = collection.parse("[(1,2,3),(4,5,6)]")
    val Parsed.Success(value2, _) = collection.parse("[(1, 2, 3), 7, 6]")
    val Parsed.Success(value3, _) = collection.parse("""[("1", "2", 3), 7, 6]""")
    val Parsed.Success(value4, _) = ioParser.parse(""""test-mapping" "bookmark" [("name","string","not_analyzed"),("created_at","date")]""")
    assert(value === ArrayBuffer(ArrayBuffer(1,2, 3), ArrayBuffer(4, 5, 6)))
    assert(value2 === ArrayBuffer(ArrayBuffer(1, 2, 3), 7, 6))
    assert(value3 === ArrayBuffer(ArrayBuffer("1", "2", 3), 7, 6))
    println(value4)
  }
}
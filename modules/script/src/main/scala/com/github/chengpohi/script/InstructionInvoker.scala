package com.github.chengpohi.script

import com.github.chengpohi.parser.EQLParser
import com.github.chengpohi.parser.collection.JsonCollection
import org.apache.commons.lang3.StringUtils

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.nio.file.{Files, Paths}
import java.util.stream.Collectors
import scala.collection.mutable
import scala.util.{Failure, Success}

trait InstructionInvoker {
  val eqlParser: EQLParser
  val client: HttpClient = HttpClient.newHttpClient()

  import eqlParser._

  def invokeInstruction(invokeIns: Seq[eqlParser.Instruction2],
                        scriptContextIns: Seq[eqlParser.Instruction2],
                        runDir: String): EQLRunResult = {

    val endpointBind = scriptContextIns.find(_.isInstanceOf[EndpointBindInstruction])
      .map(i => i.asInstanceOf[eqlParser.EndpointBindInstruction])
    if (endpointBind.isEmpty) {
      return EQLRunResult(Failure(new RuntimeException("need bind host")))
    }

    val (functions, context) =
      this.buildContext(scriptContextIns, endpointBind.get.endpoint, runDir)

    val invokeResult = invokeIns.map {
      case invokeFunction: FunctionInvokeInstruction =>
        functionInvoke(functions, context, invokeFunction)
      case e: EchoInstruction => {
        e.value match {
          case Left(jv) => {
            mapRealValue(context.variables, jv)
            Seq(jv.toJson)
          }
          case Right(f) => functionInvoke(functions, context, f)
        }
      }
      case it: ForInstruction => {
        iterCollection(functions, context, it)
      }
      case i =>
        Seq(i.execute(context).json)
    }

    EQLRunResult(invokeResult.filter(i => i != null).map(_.filter(_.nonEmpty)), context)
  }

  private def buildContext(cIns: Seq[eqlParser.Instruction2],
                           endPoint: String,
                           runDir: String) = {
    val importIns = parseImports(cIns, runDir)

    val invokeIns = cIns ++ importIns

    val authorization =
      invokeIns.find(_.isInstanceOf[AuthorizationBindInstruction])
        .map(i => i.asInstanceOf[AuthorizationBindInstruction])
        .map(i => i.auth)

    val username =
      invokeIns.find(_.isInstanceOf[UsernameBindInstruction])
        .map(i => i.asInstanceOf[UsernameBindInstruction])
        .map(i => i.username)

    val password =
      invokeIns.find(_.isInstanceOf[PasswordBindInstruction])
        .map(i => i.asInstanceOf[PasswordBindInstruction])
        .map(i => i.password)
    val apikeyId =
      invokeIns.find(_.isInstanceOf[ApiKeyIdBindInstruction])
        .map(i => i.asInstanceOf[ApiKeyIdBindInstruction])
        .map(i => i.apikeyId)
    val apikeySecret =
      invokeIns.find(_.isInstanceOf[ApiKeSecretBindInstruction])
        .map(i => i.asInstanceOf[ApiKeSecretBindInstruction])
        .map(i => i.apiSecret)

    val timeout =
      invokeIns.find(_.isInstanceOf[TimeoutInstruction])
        .map(i => i.asInstanceOf[TimeoutInstruction])
        .map(i => i.timeout)


    val vars =
      invokeIns.filter(_.isInstanceOf[VariableInstruction])
        .map(i => i.asInstanceOf[VariableInstruction])
        .map(i => i.variableName -> i.value).toMap

    val globalFunctions =
      invokeIns.filter(_.isInstanceOf[FunctionInstruction])
        .map(i => i.asInstanceOf[FunctionInstruction])
        .map(i => i.funcName + i.variableNames.size -> i)
        .toMap ++ systemFunction

    val globalVars = vars.filter(_._2.isLeft).map(i => i._1 -> i._2.left.get) + ("CONTEXT_PATH" -> JsonCollection.Str(runDir))

    val context = ScriptEQLContext(
      endPoint,
      authorization,
      username,
      password,
      apikeyId,
      apikeySecret,
      timeout,
      globalVars)

    evaluateFunctionVars(globalFunctions, context, vars)

    (globalFunctions, context)
  }

  private def parseImports(cIns: Seq[eqlParser.Instruction2], runDir: String): Seq[eqlParser.Instruction2] = {
    val imports =
      cIns.filter(_.isInstanceOf[ImportInstruction])
        .map(i => i.asInstanceOf[ImportInstruction])

    if (imports.isEmpty) {
      return Seq()
    }

    val importStr = imports.map(i => {
      handleImport(runDir, client, i)
    }).mkString("").trim

    val importIns = eqlParser.generateInstructions(importStr) match {
      case Success(ins) => ins
      case Failure(f) => throw new RuntimeException("import parse failed" + f.getMessage, f)
    }
    importIns ++ parseImports(importIns, runDir).filter(i => i.isInstanceOf[FunctionInstruction] || i.isInstanceOf[VariableInstruction])
  }

  private def handleImport(runDir: String, client: HttpClient, i: eqlParser.ImportInstruction): String = {
    val imp = i.imp
    val content = readFile(runDir, imp)
    if (!StringUtils.isEmpty(content)) {
      return content
    }
    val body = readFromWeb(client, imp)

    if (!StringUtils.isEmpty(body)) {
      return body
    }

    throw new RuntimeException("could not found content from " + imp)
  }

  private def readFromWeb(client: HttpClient, imp: String): String = {
    try {
      val httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(imp))
        .GET()
        .build();
      val response = client.send(httpRequest, BodyHandlers.ofString())
      response.body();
    } catch {
      case _: Throwable => ""
    }
  }

  private def readFile(runDir: String, imp: String): String = {
    try {
      Files.readAllLines(Paths.get(runDir + "/" + imp)).stream().collect(Collectors.joining(System.lineSeparator()))
    } catch {
      case _: Throwable => ""
    }
  }

  private def evaluateFunctionVars(globalFunctions: Map[String, eqlParser.FunctionInstruction],
                                   context: ScriptEQLContext,
                                   vars: Map[String, eqlParser.ContextVal]
                                  ) = {
    val evaluateVars = vars.filter(_._2.isRight).map(i => i._1 -> i._2.right.get)
    evaluateVars.foreach(fVar => {
      val value = functionInvoke(globalFunctions, context, fVar._2).last
      val fVal = parseJson(value)
      if (fVal.isFailure) {
        throw new RuntimeException(fVal.failed.get)
      }
      context.variables.put(fVar._1, fVal.get)
    })
    val valVars = vars.filter(_._2.isLeft).map(i => i._1 -> i._2.left.get)
    context.variables.addAll(valVars)
  }

  def extractCollection(iterVariable: JsonCollection.Val): JsonCollection.Arr = {
    iterVariable match {
      case c: JsonCollection.Arr => c
      case t: JsonCollection.Var if t.realValue.get.isInstanceOf[JsonCollection.Arr] =>
        t.realValue.get.asInstanceOf[JsonCollection.Arr]
      case _ =>
        throw new RuntimeException("iter variable not a collection")
    }
  }


  def iterCollection(functions: Map[String, eqlParser.FunctionInstruction],
                     context: ScriptEQLContext,
                     r: eqlParser.ForInstruction): Seq[String] = {
    val cachedVariables = context.variables

    val iterVariable = r.iterVariable
    mapRealValue(context.variables, iterVariable)

    val instructions = extractCollection(iterVariable).value.flatMap(i => {
      context.variables = mutable.Map[String, JsonCollection.Val](cachedVariables.toSeq: _*)
      context.variables.put(r.tempVariable, i)
      runInstructions(functions, context, r.instructions)
    })

    context.variables = cachedVariables
    instructions
  }


  def functionInvoke(functions: Map[String, eqlParser.FunctionInstruction],
                     context: ScriptEQLContext,
                     invoke: eqlParser.FunctionInvokeInstruction): Seq[String] = {
    val cachedVariables = context.variables
    val values = invoke.vals

    val foundFunction = functions.get(invoke.funcName + values.size)
    if (foundFunction.isEmpty) {
      throw new RuntimeException("Could not found method: " + invoke.funcName + " with parameters " + values.map(_.left).mkString(","))
    }

    val func = foundFunction.get
    val funcMethodVars = func.variableNames.zip(values).toMap

    evaluateFunctionVars(functions, context, funcMethodVars)

    context.variables = mutable.Map[String, JsonCollection.Val](cachedVariables.toSeq: _*)

    val instructions = func.instructions
    val funcBodyVars = instructions.filter(_.isInstanceOf[VariableInstruction])
      .map(i => i.asInstanceOf[VariableInstruction])
      .map(i => i.variableName -> i.value).toMap

    evaluateFunctionVars(functions, context, funcBodyVars)

    val response = runInstructions(functions, context, instructions)

    context.variables = cachedVariables
    response
  }

  def runInstructions(functions: Map[String, eqlParser.FunctionInstruction],
                      context: ScriptEQLContext,
                      instructions: Seq[eqlParser.Instruction2]): Seq[String] = {
    instructions
      .filter(!_.isInstanceOf[ScriptContextInstruction2]).flatMap {
      case r: ForInstruction => {
        iterCollection(functions, context, r)
      }
      case f: FunctionInvokeInstruction =>
        functionInvoke(functions, context, f)
      case r: ReturnInstruction => {
        r.value match {
          case Left(jv) => {
            mapRealValue(context.variables, jv)
            Seq(jv.toJson)
          }
          case Right(f) => functionInvoke(functions, context, f)
        }
      }
      case r: EchoInstruction =>
        r.value match {
          case Left(jv) => {
            mapRealValue(context.variables, jv)
            Seq(jv.toJson)
          }
          case Right(f) => functionInvoke(functions, context, f)
        }
      case i => {
        Seq(i.execute(context).json)
      }
    }
  }
}

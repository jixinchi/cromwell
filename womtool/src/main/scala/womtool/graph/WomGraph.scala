package womtool.graph

import java.nio.file.{Files, Paths}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import better.files.File
import cats.implicits._
import cwl.CwlDecoder
import cwl.preprocessor.CwlPreProcessor
import spray.json.{JsArray, JsBoolean, JsNull, JsNumber, JsObject, JsString, JsValue}
import wdl.transforms.draft2.wdlom2wom._
import wom.transforms.WomExecutableMaker.ops._
import wdl.draft2.model.{WdlNamespace, WdlNamespaceWithWorkflow}
import wom.executable.Executable
import wom.graph._
import wom.types._
import womtool.graph.WomGraph._

import scala.collection.JavaConverters._

class WomGraph(graphName: String, graph: Graph) {

  def indent(s: String) = s.lines.map(x => s"  $x").mkString(System.lineSeparator)
  def combine(ss: Iterable[String]) = ss.mkString(start="", sep=System.lineSeparator, end=System.lineSeparator)
  def indentAndCombine(ss: Iterable[String]) = combine(ss.map(indent))
  implicit val monoid = cats.derive.monoid[NodesAndLinks]

  val digraphDot: String = {

    val workflowDigraph = WorkflowDigraph (dotSafe(graphName), listAllGraphNodes(graph))

    s"""|digraph ${workflowDigraph.workflowName}
        |{
        |  compound=true;
        |${indentAndCombine(workflowDigraph.digraph.links)}
        |${indentAndCombine(workflowDigraph.digraph.nodes)}
        |}
        |""".stripMargin

  }

  private lazy val clusterCount: AtomicInteger = new AtomicInteger(0)

  private[graph] def listAllGraphNodes(graph: Graph): NodesAndLinks = {
    graph.nodes foldMap nodesAndLinks
  }

  private def upstreamLinks(graphNode: GraphNode): Set[String] = graphNode match {
    case ogin: OuterGraphInputNode => Set(s"${ogin.linkToOuterGraph.graphId} -> ${ogin.singleOutputPort.graphId} [style=dashed arrowhead=none]")
    case _ =>
      for {
        inputPort <- graphNode.inputPorts
        upstreamPort = inputPort.upstream
      } yield s"${upstreamPort.graphId} -> ${inputPort.graphId}"

  }

  private def portLine(p: GraphNodePort) = s"${p.graphId} [shape=${p.graphShape} label=${p.graphName}];"

  private def portLines(graphNode: GraphNode): String = graphNode match {
    case s: ScatterNode =>
      // Don't include the scatter expression input port here since they're added later in `internalScatterNodesAndLinks`
      // Round up the gathered output ports so it's obvious they're being gathered.
      s"""
        |${combine((s.inputPorts -- s.scatterCollectionExpressionNodes.flatMap(_.inputPorts)) map portLine)}
        |subgraph $nextCluster {
        |  style=${s.graphStyle};
        |  fillcolor=${s.graphFillColor}
        |  "${UUID.randomUUID}" [shape=plaintext label="gather ports"]
        |${indentAndCombine(s.outputPorts map portLine)}
        |}
      """.stripMargin
    case _ => combine((graphNode.outputPorts ++ graphNode.inputPorts) map portLine)
  }

  /**
    * For most graph nodes, we draw a single box for the node, containing the name and input and output ports.
    * For each input/output port, we include the links to upstream ports.
    */
  private def nodesAndLinks(graphNode: GraphNode): NodesAndLinks = {
    val internals = internalNodesAndLinks(graphNode)
    val clusterName = nextCluster

    val singleNode =
      s"""
         |subgraph $clusterName {
         |  style=${graphNode.graphStyle};
         |  fillcolor=${graphNode.graphFillColor};
         |  ${graphNode.graphId} [shape=plaintext label=${graphNode.graphName}]
         |${indent(portLines(graphNode))}
         |${indentAndCombine(internals.nodes)}
         |}
         |""".stripMargin

    NodesAndLinks(Set(singleNode), internals.links ++ upstreamLinks(graphNode))
  }

  private def internalNodesAndLinks(graphNode: GraphNode): NodesAndLinks = graphNode match {
    case scatter: ScatterNode => internalScatterNodesAndLinks(scatter)
    case conditional: ConditionalNode => internalConditionalNodesAndLinks(conditional)
    case subworkflow: WorkflowCallNode => internalSubworkflowNodesAndLinks(subworkflow)
    case _ => NodesAndLinks.empty
  }

  def internalScatterNodesAndLinks(scatter: ScatterNode) = {
    val innerGraph = listAllGraphNodes(scatter.innerGraph) wrapNodes { n =>
      s"""
         |subgraph $nextCluster {
         |  style="filled,solid";
         |  fillcolor=white;
         |${indentAndCombine(n)}
         |}
         |""".stripMargin
    }

    val outputLinks = scatter.outputMapping map { outputPort => s"${outputPort.outputToGather.singleInputPort.graphId} -> ${outputPort.graphId} [style=dashed arrowhead=none]" }
    innerGraph.withLinks(outputLinks)
  }

  def internalConditionalNodesAndLinks(conditional: ConditionalNode): NodesAndLinks = {
    val innerGraph = listAllGraphNodes(conditional.innerGraph) wrapNodes { n =>
      s"""
         |subgraph $nextCluster {
         |  style=filled;
         |  fillcolor=white;
         |${indentAndCombine(n)}
         |}
         |""".stripMargin
    }

    val outputLinks = conditional.conditionalOutputPorts map { outputPort => s"${outputPort.outputToExpose.singleInputPort.graphId} -> ${outputPort.graphId} [style=dashed arrowhead=none]" }
    innerGraph.withLinks(outputLinks)
  }

  def internalSubworkflowNodesAndLinks(subworkflow: WorkflowCallNode): NodesAndLinks = listAllGraphNodes(subworkflow.callable.innerGraph) wrapNodes { n =>
    s"""
       |subgraph $nextCluster {
       |  style=filled;
       |  fillcolor=white;
       |${indentAndCombine(n)}
       |}
       |""".stripMargin
  }

  private def nextCluster: String = "cluster_" + clusterCount.getAndIncrement()
}

object WomGraph {
  
  implicit val cwlPreProcessor = CwlPreProcessor.noLogging

  final case class WorkflowDigraph(workflowName: String, digraph: NodesAndLinks)
  final case class NodesAndLinks(nodes: Set[String], links: Set[String]) {
    def wrapNodes(f: Set[String] => String) = this.copy(Set(f(nodes)), links)
    def withLinks(ls: Set[String]) = this.copy(links = links ++ ls)
  }

  object NodesAndLinks {
    val empty = NodesAndLinks(Set.empty, Set.empty)
  }

  def fromFiles(mainFile: String, auxFiles: Seq[String]) = {
    val womExecutable = if (mainFile.toLowerCase().endsWith("wdl")) womExecutableFromWdl(mainFile) else womExecutableFromCwl(mainFile)
    new WomGraph(womExecutable.entryPoint.name, womExecutable.graph)
  }

  private def readFile(filePath: String): String = Files.readAllLines(Paths.get(filePath)).asScala.mkString(System.lineSeparator())

  private def womExecutableFromWdl(filePath: String): Executable = {
    val namespace = WdlNamespaceWithWorkflow.load(readFile(filePath), Seq(WdlNamespace.fileResolver _)).get
    // TODO: Remove this and the 'fakeInput' method with #2867
    val fakedInputs = JsObject(namespace.workflow.inputs map { i => i._1 -> fakeInput(i._2.womType) })

    namespace.toWomExecutable(Option(fakedInputs.prettyPrint)) match {
      case Right(wom) => wom
      case Left(e) => throw new Exception(s"Can't build WOM executable from WDL namespace: ${e.toList.mkString("\n", "\n", "\n")}")
    }
  }

  private def womExecutableFromCwl(filePath: String): Executable = {
    import cwl.AcceptAllRequirements
    (for {
      clt <- CwlDecoder.decodeCwlFile(File(filePath)).
        value.
        unsafeRunSync
      inputs = clt.requiredInputs
      fakedInputs = JsObject(inputs map { i => i._1 -> fakeInput(i._2) })
      wom <- clt.womExecutable(AcceptAllRequirements, Option(fakedInputs.prettyPrint))
    } yield wom) match {
      case Right(womExecutable) => womExecutable
      case Left(e) => throw new Exception(s"Can't build WOM executable from CWL: ${e.toList.mkString("\n", "\n", "\n")}")
    }
  }

  def fakeInput(womType: WomType): JsValue = womType match {
    case WomStringType => JsString("hio")
    case WomIntegerType | WomFloatType => JsNumber(25)
    case WomUnlistedDirectoryType => JsString("gs://bucket/path")
    case WomSingleFileType => JsString("gs://bucket/path/file.txt")
    case WomBooleanType => JsBoolean(true)
    case _: WomOptionalType => JsNull
    case WomMapType(_, valueType) => JsObject(Map("0" -> fakeInput(valueType)))
    case WomArrayType(innerType) => JsArray(Vector(fakeInput(innerType)))
    case WomPairType(leftType, rightType) => JsObject(Map("left" -> fakeInput(leftType), "right" -> fakeInput(rightType)))
  }
}

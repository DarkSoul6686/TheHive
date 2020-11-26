package org.thp.thehive.controllers.v1

import java.lang.{Long => JLong}
import java.util.{List => JList, Map => JMap}

import org.apache.tinkerpop.gremlin.structure.Vertex
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{Converter, Traversal}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.models.Log
import org.thp.thehive.services.CaseOps._
import org.thp.thehive.services.LogOps._
import org.thp.thehive.services.TaskOps._
import play.api.libs.json._

trait LogRenderer {

  def caseParent(implicit
      authContext: AuthContext
  ): Traversal.V[Log] => Traversal[JsValue, JList[JMap[String, Any]], Converter[JsValue, JList[JMap[String, Any]]]] =
    _.`case`.richCase.fold.domainMap(_.headOption.fold[JsValue](JsNull)(_.toJson))

  def taskParent(implicit
      authContext: AuthContext
  ): Traversal.V[Log] => Traversal[JsValue, JMap[String, Any], Converter[JsValue, JMap[String, Any]]] =
    _.task.project(_.by(_.richTask.fold).by(_.`case`.richCase.fold)).domainMap {
      case (task, case0) =>
        task.headOption.fold[JsValue](JsNull)(_.toJson.as[JsObject] + ("case" -> case0.headOption.fold[JsValue](JsNull)(_.toJson)))
    }

  def taskParentId: Traversal.V[Log] => Traversal[JsValue, JList[Vertex], Converter[JsValue, JList[Vertex]]] =
    _.task.fold.domainMap(_.headOption.fold[JsValue](JsNull)(c => JsString(c._id.toString)))

  def actionCount: Traversal.V[Log] => Traversal[JsValue, JLong, Converter[JsValue, JLong]] =
    _.in("ActionContext").count.domainMap(JsNumber(_))

  def logStatsRenderer(extraData: Set[String])(implicit
      authContext: AuthContext
  ): Traversal.V[Log] => Traversal[JsObject, JMap[String, Any], Converter[JsObject, JMap[String, Any]]] = { traversal =>
    def addData[G](
        name: String
    )(f: Traversal.V[Log] => Traversal[JsValue, G, Converter[JsValue, G]]): Traversal[JsObject, JMap[String, Any], Converter[
      JsObject,
      JMap[String, Any]
    ]] => Traversal[JsObject, JMap[String, Any], Converter[JsObject, JMap[String, Any]]] = { t =>
      val dataTraversal = f(traversal.start)
      t.onRawMap[JsObject, JMap[String, Any], Converter[JsObject, JMap[String, Any]]](_.by(dataTraversal.raw)) { jmap =>
        t.converter(jmap) + (name -> dataTraversal.converter(jmap.get(name).asInstanceOf[G]))
      }
    }

    if (extraData.isEmpty) traversal.constant2[JsObject, JMap[String, Any]](JsObject.empty)
    else {
      val dataName = extraData.toSeq
      dataName.foldLeft[Traversal[JsObject, JMap[String, Any], Converter[JsObject, JMap[String, Any]]]](
        traversal.onRawMap[JsObject, JMap[String, Any], Converter[JsObject, JMap[String, Any]]](_.project(dataName.head, dataName.tail: _*))(_ =>
          JsObject.empty
        )
      ) {
        case (f, "case")        => addData("case")(caseParent)(f)
        case (f, "task")        => addData("task")(taskParent)(f)
        case (f, "taskId")      => addData("taskId")(taskParentId)(f)
        case (f, "actionCount") => addData("actionCount")(actionCount)(f)
        case (f, _)             => f
      }
    }
  }
}
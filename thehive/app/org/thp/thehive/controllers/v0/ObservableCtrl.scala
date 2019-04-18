package org.thp.thehive.controllers.v0

import scala.util.Success

import play.api.Logger
import play.api.libs.json.{JsArray, JsObject}
import play.api.mvc.{Action, AnyContent, Results}

import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.controllers._
import org.thp.scalligraph.models.{Database, ResultWithTotalSize}
import org.thp.scalligraph.query.Query
import org.thp.thehive.dto.v0.{InputObservable, InputTask}
import org.thp.thehive.models._
import org.thp.thehive.services.{CaseSrv, ObservableSrv}

@Singleton
class ObservableCtrl @Inject()(
    entryPoint: EntryPoint,
    db: Database,
    observableSrv: ObservableSrv,
    caseSrv: CaseSrv,
    val queryExecutor: TheHiveQueryExecutor)
    extends QueryCtrl
    with ObservableConversion {

  lazy val logger = Logger(getClass)

  def create(caseId: String): Action[AnyContent] =
    entryPoint("create artifact")
      .extract('artifact, FieldsParser[InputObservable])
      .authenticated { implicit request ⇒
        db.tryTransaction { implicit graph ⇒
          val inputObservable: InputObservable = request.body('artifact)
          caseSrv.getOrFail(caseId).map { `case` ⇒
            val dataOrFile: Either[Data, FFile] = inputObservable.data.map(Data.apply).toLeft(inputObservable.attachment.get)
            val createdObservable               = observableSrv.create(inputObservable, dataOrFile, Nil, `case`)
            Results.Created(createdObservable.toJson)
          }
        }
      }

  def get(observableId: String): Action[AnyContent] =
    entryPoint("get observable")
      .authenticated { _ ⇒
        db.tryTransaction { implicit graph ⇒
          observableSrv
            .get(observableId)
            //            .availableFor(request.organisation)
            .richObservable
            .getOrFail()
            .map { observable ⇒
              Results.Ok(observable.toJson)
            }
        }
      }

//  def list: Action[AnyContent] =
//    entryPoint("list task")
//      .authenticated { implicit request ⇒
//        db.transaction { implicit graph ⇒
//          val observables = observableSrv.initSteps
////            .availableFor(request.organisation)
//              .richObservable
//            .toList()
//            .map(_.toJson)
//          Results.Ok(Json.toJson(observables))
//        }
//      }

  def update(taskId: String): Action[AnyContent] =
    entryPoint("update task")
      .extract('task, UpdateFieldsParser[InputTask])
      .authenticated { _ ⇒
        db.transaction { _ ⇒
//          if (observableSrv.isAvailableFor(taskId)) {
//            observableSrv.update(taskId, outputTaskProperties(db), request.body('task))
//            Results.NoContent
//          } else Results.Unauthorized(s"Task $taskId doesn't exist or permission is insufficient")
          ???
        }
      }

  def stats(): Action[AnyContent] = {
    val parser: FieldsParser[Seq[Query]] = statsParser("listObservable")
    entryPoint("stats task")
      .extract('query, parser)
      .authenticated { implicit request ⇒
        val queries: Seq[Query] = request.body('query)
        val results = queries
          .map { query ⇒
            db.transaction { graph ⇒
              queryExecutor.execute(query, graph, request.authContext).toJson
            }
          }
          .foldLeft(JsObject.empty) {
            case (acc, o: JsObject) ⇒ acc ++ o
            case (acc, r) ⇒
              logger.warn(s"Invalid stats result: $r")
              acc
          }
        Success(Results.Ok(results))
      }
  }

  def search: Action[AnyContent] =
    entryPoint("search case")
      .extract('query, searchParser("listObservable", paged = false))
      .authenticated { implicit request ⇒
        val query: Query = request.body('query)
        val result = db.transaction { graph ⇒
          queryExecutor.execute(query, graph, request.authContext)
        }
        val resp = (result.toJson \ "result").as[JsArray]
        result.toOutput match {
          case ResultWithTotalSize(_, size) ⇒ Success(Results.Ok(resp).withHeaders("X-Total" → size.toString))
          case _                            ⇒ Success(Results.Ok(resp).withHeaders("X-Total" → resp.value.size.toString))
        }
      }
}
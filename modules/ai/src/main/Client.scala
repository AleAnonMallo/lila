package lila.ai

import actorApi._

import akka.actor._
import akka.pattern.ask
import scala.concurrent.duration._

import chess.format.UciMove
import lila.analyse.Info
import lila.game.{ Game, GameRepo }
import play.api.libs.ws.{ WS, WSRequestHolder }
import play.api.Play.current

final class Client(
    config: Config,
    endpoint: String,
    callbackUrl: String,
    val uciMemo: lila.game.UciMemo) {

  private def withValidSituation[A](game: Game)(op: => Fu[A]): Fu[A] =
    if (game.toChess.situation playable true) op
    else fufail("[ai stockfish] invalid position")

  def play(game: Game, level: Int): Fu[PlayResult] = withValidSituation(game) {
    val aiVariant = Variant(game.variant)
    for {
      storedFen ← GameRepo initialFen game
      fen = storedFen orElse (aiVariant match {
        case v@Horde => v.initialFen.some
        case _       => none
      })
      uciMoves ← uciMemo get game
      moveResult ← move(uciMoves.toList, fen, level, aiVariant)
      uciMove ← (UciMove(moveResult.move) toValid s"${game.id} wrong bestmove: $moveResult").future
      result ← game.toChess(uciMove.orig, uciMove.dest, uciMove.promotion).future
      (c, move) = result
      progress = game.update(c, move)
      _ ← (GameRepo save progress) >>- uciMemo.add(game, uciMove.uci)
    } yield PlayResult(progress, move)
  }

  def move(uciMoves: List[String], initialFen: Option[String], level: Int, variant: Variant): Fu[MoveResult] = {
    sendRequest(true) {
      WS.url(s"$endpoint/move").withQueryString(
        "uciMoves" -> uciMoves.mkString(" "),
        "initialFen" -> ~initialFen,
        "level" -> level.toString,
        "variant" -> variant.toString)
    } map MoveResult.apply
  }

  def analyse(gameId: String, uciMoves: List[String], initialFen: Option[String], requestedByHuman: Boolean, variant: Variant) {
    WS.url(s"$endpoint/analyse").withQueryString(
      "replyUrl" -> callbackUrl.replace("%", gameId),
      "uciMoves" -> uciMoves.mkString(" "),
      "initialFen" -> ~initialFen,
      "human" -> requestedByHuman.fold("1", "0"),
      "gameId" -> gameId,
      "variant" -> variant.toString).post("go")
  }

  private def sendRequest(retriable: Boolean)(req: WSRequestHolder): Fu[String] =
    req.get flatMap {
      case res if res.status == 200 => fuccess(res.body)
      case res =>
        val message = s"AI client WS response ${res.status} ${~res.body.lines.toList.headOption}"
        if (retriable) {
          _root_.play.api.Logger("AI client").error(s"Retry: $message")
          sendRequest(false)(req)
        }
        else fufail(message)
    }
}

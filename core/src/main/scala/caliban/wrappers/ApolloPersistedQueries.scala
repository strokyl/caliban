package caliban.wrappers

import caliban.CalibanError.ValidationError
import caliban.Value.{ NullValue, StringValue }
import caliban.wrappers.Wrapper.OverallWrapper
import caliban.{ CalibanError, GraphQLRequest, GraphQLResponse, InputValue }
import zio.{ Accessible, Layer, Ref, UIO, ZIO }

object ApolloPersistedQueries {

  trait ApolloPersistence {
    def get(hash: String): UIO[Option[String]]
    def add(hash: String, query: String): UIO[Unit]
  }

  object ApolloPersistence extends Accessible[ApolloPersistence]

  case class ApolloPersistenceLive(cache: Ref[Map[String, String]]) extends ApolloPersistence {
    def get(hash: String): UIO[Option[String]]      = cache.get.map(_.get(hash))
    def add(hash: String, query: String): UIO[Unit] = cache.update(_.updated(hash, query))
  }

  val live: Layer[Nothing, ApolloPersistence] =
    Ref.make[Map[String, String]](Map()).toLayer >>> (ApolloPersistenceLive.apply _).toLayer

  /**
   * Returns a wrapper that persists and retrieves queries based on a hash
   * following Apollo Persisted Queries spec: https://github.com/apollographql/apollo-link-persisted-queries.
   */
  val apolloPersistedQueries: OverallWrapper[ApolloPersistence] =
    new OverallWrapper[ApolloPersistence] {
      def wrap[R1 <: ApolloPersistence](
        process: GraphQLRequest => ZIO[R1, Nothing, GraphQLResponse[CalibanError]]
      ): GraphQLRequest => ZIO[R1, Nothing, GraphQLResponse[CalibanError]] =
        (request: GraphQLRequest) =>
          readHash(request) match {
            case Some(hash) =>
              ApolloPersistence(_.get(hash)).flatMap {
                case Some(query) => UIO(request.copy(query = Some(query)))
                case None        =>
                  request.query match {
                    case Some(value) => ApolloPersistence(_.add(hash, value)).as(request)
                    case None        => ZIO.fail(ValidationError("PersistedQueryNotFound", ""))
                  }

              }
                .flatMap(process)
                .catchAll(ex => UIO(GraphQLResponse(NullValue, List(ex))))
            case None       => process(request)
          }
    }

  private def readHash(request: GraphQLRequest): Option[String] =
    request.extensions
      .flatMap(_.get("persistedQuery"))
      .flatMap {
        case InputValue.ObjectValue(fields) =>
          fields.get("sha256Hash").collectFirst { case StringValue(hash) =>
            hash
          }
        case _                              => None
      }
}

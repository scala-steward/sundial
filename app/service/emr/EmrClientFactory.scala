package service.emr

import cats.effect.{IO, Resource}
import com.amazonaws.regions.Regions
import com.amazonaws.services.elasticmapreduce.{
  AmazonElasticMapReduce,
  AmazonElasticMapReduceClientBuilder
}
import javax.inject.Singleton

@Singleton
class EmrClientFactory {

  def emrClientResource(
      region: Regions): Resource[IO, AmazonElasticMapReduce] = {
    Resource.make(
      IO(
        AmazonElasticMapReduceClientBuilder
          .standard()
          .withRegion(region)
          .build()))(client => IO(client.shutdown()))
  }

}

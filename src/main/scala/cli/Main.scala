package cli

import map_generator.{Generator, GeneratorConfig}

object Main {
  def main(args: Array[String]): Unit = {
    val config = GeneratorConfig.medium
    val generator = new Generator(config)
    generator.generate
  }
}

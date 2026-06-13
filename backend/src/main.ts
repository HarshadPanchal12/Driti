import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  const port = process.env.PORT ?? 3008;
  await app.listen(port);
  console.log(`Tablet remote backend listening on http://localhost:${port}`);
}

bootstrap();

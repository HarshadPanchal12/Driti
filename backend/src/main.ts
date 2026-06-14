import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  app.enableCors({
    origin: process.env.CORS_ORIGIN?.split(',') ?? ['http://localhost:5178'],
    credentials: true,
  });
  const port = process.env.PORT ?? 3008;
  await app.listen(port);
  console.log(`Tablet remote backend listening on http://localhost:${port}`);
}

bootstrap();

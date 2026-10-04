import { NestFactory } from '@nestjs/core';
import { NestExpressApplication } from '@nestjs/platform-express';
import { AppModule } from './app.module';
import { VncTunnelService } from './vnc/vnc-tunnel.service';

async function bootstrap() {
  const app = await NestFactory.create<NestExpressApplication>(AppModule);

  app.useBodyParser('json', { limit: '2mb' });
  app.useBodyParser('urlencoded', { extended: true, limit: '2mb' });

  app.enableCors({
    origin: process.env.CORS_ORIGIN?.split(',') ?? ['http://localhost:5178'],
    credentials: true,
  });
  const port = process.env.PORT ?? 3008;
  await app.listen(port, '0.0.0.0');


  const vncTunnelService = app.get(VncTunnelService);
  vncTunnelService.attach(app.getHttpServer());

  console.log(`Tablet remote backend listening on http://localhost:${port}`);
  console.log(`VNC relay: ws://localhost:${port}/vnc/viewer/:deviceId?key=<api-key>`);
}

bootstrap();

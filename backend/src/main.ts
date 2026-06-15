import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { VncTunnelService } from './vnc/vnc-tunnel.service';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  app.enableCors({
    origin: process.env.CORS_ORIGIN?.split(',') ?? ['http://localhost:5178'],
    credentials: true,
  });
  const port = process.env.PORT ?? 3008;
  await app.listen(port);

  const vncTunnelService = app.get(VncTunnelService);
  vncTunnelService.attach(app.getHttpServer());

  console.log(`Tablet remote backend listening on http://localhost:${port}`);
  console.log(`VNC relay: ws://localhost:${port}/vnc/viewer/:deviceId?key=<api-key>`);
}

bootstrap();

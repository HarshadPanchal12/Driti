import { Module } from '@nestjs/common';
import { DevicesModule } from './devices/devices.module';
import { HealthController } from './health.controller';
import { VncModule } from './vnc/vnc.module';

@Module({
  imports: [DevicesModule, VncModule],
  controllers: [HealthController],
})
export class AppModule {}

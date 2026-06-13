import { Module } from '@nestjs/common';
import { DevicesController } from './devices/devices.controller';
import { DevicesService } from './devices/devices.service';
import { HealthController } from './health.controller';

@Module({
  controllers: [HealthController, DevicesController],
  providers: [DevicesService],
})
export class AppModule {}

import { Module } from '@nestjs/common';
import { DevicesModule } from '../devices/devices.module';
import { VncTunnelService } from './vnc-tunnel.service';

@Module({
  imports: [DevicesModule],
  providers: [VncTunnelService],
  exports: [VncTunnelService],
})
export class VncModule {}

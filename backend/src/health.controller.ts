import { Controller, Get } from '@nestjs/common';

@Controller()
export class HealthController {
  @Get()
  root() {
    return {
      status: 'ok',
      service: 'tablet-remote-backend',
      endpoints: [
        'POST /devices/:deviceId/heartbeat',
        'GET /devices/:deviceId/command',
        'POST /devices/:deviceId/result',
        'POST /devices/:deviceId/enqueue-command',
      ],
    };
  }
}

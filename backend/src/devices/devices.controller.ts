import {
  Body,
  Controller,
  Get,
  Param,
  Post,
  UseGuards,
} from '@nestjs/common';
import { ApiKeyGuard } from '../auth/api-key.guard';
import { CommandType } from './command.types';
import { DevicesService } from './devices.service';

class EnqueueCommandDto {
  type!: CommandType;
  payload?: unknown;
}

class SubmitResultDto {
  commandId!: string;
  success!: boolean;
  output?: unknown;
}

@Controller('devices')
@UseGuards(ApiKeyGuard)
export class DevicesController {
  constructor(private readonly devicesService: DevicesService) {}

  @Post(':deviceId/heartbeat')
  heartbeat(@Param('deviceId') deviceId: string) {
    return this.devicesService.heartbeat(deviceId);
  }

  @Get(':deviceId/command')
  pollCommand(@Param('deviceId') deviceId: string) {
    return this.devicesService.pollCommand(deviceId);
  }

  @Post(':deviceId/result')
  submitResult(
    @Param('deviceId') deviceId: string,
    @Body() body: SubmitResultDto,
  ) {
    return this.devicesService.submitResult(
      deviceId,
      body.commandId,
      body.success,
      body.output,
    );
  }

  @Post(':deviceId/enqueue-command')
  enqueueCommand(
    @Param('deviceId') deviceId: string,
    @Body() body: EnqueueCommandDto,
  ) {
    return this.devicesService.enqueueCommand(
      deviceId,
      body.type,
      body.payload,
    );
  }
}

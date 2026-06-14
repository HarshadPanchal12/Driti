import {
  Body,
  Controller,
  Get,
  Header,
  Param,
  Post,
  Res,
  UseGuards,
} from '@nestjs/common';
import { Response } from 'express';
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

class ScreenCaptureDto {
  enabled!: boolean;
}

class ScreenshotUploadDto {
  imageBase64!: string;
}

@Controller('devices')
@UseGuards(ApiKeyGuard)
export class DevicesController {
  constructor(private readonly devicesService: DevicesService) {}

  @Get()
  listDevices() {
    return this.devicesService.listDevices();
  }

  @Get(':deviceId')
  getDevice(@Param('deviceId') deviceId: string) {
    return this.devicesService.getDevice(deviceId);
  }

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

  @Post(':deviceId/screen-capture')
  setScreenCapture(
    @Param('deviceId') deviceId: string,
    @Body() body: ScreenCaptureDto,
  ) {
    return this.devicesService.setCaptureScreen(deviceId, body.enabled);
  }

  @Post(':deviceId/screenshot')
  uploadScreenshot(
    @Param('deviceId') deviceId: string,
    @Body() body: ScreenshotUploadDto,
  ) {
    return this.devicesService.saveScreenshot(deviceId, body.imageBase64);
  }

  @Get(':deviceId/screen')
  @Header('Cache-Control', 'no-store')
  getScreen(@Param('deviceId') deviceId: string, @Res() res: Response) {
    const buffer = this.devicesService.getScreenshotBuffer(deviceId);
    res.set('Content-Type', 'image/jpeg');
    res.send(buffer);
  }
}

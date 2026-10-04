import {
  CanActivate,
  ExecutionContext,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { Request } from 'express';

@Injectable()
export class ApiKeyGuard implements CanActivate {
  private readonly expectedKey = process.env.AGENT_API_KEY ?? 'dev-agent-key';

  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<Request>();
    const key = request.header('x-agent-key');

    if (!key || key !== this.expectedKey) {
      throw new UnauthorizedException('Invalid or missing x-agent-key');
    }

    return true;
  }
}

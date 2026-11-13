export const MAX_PHONE_LENSES: number = 4;

export class LensInfo {
  id: string = '';
  label: string = '';
  position: number = 0;
  cameraType: number = 0;
  source: string = '';
  physical: boolean = false;
  logical: boolean = false;
  parentId: string = '';
}

export class CapturedPhoto {
  lensId: string = '';
  lensLabel: string = '';
  zoom: number = 1;
  capturedAt: number = 0;
  captureMode: string = 'SINGLE';
  shutterTimestampMs: number = 0;
  jpeg: ArrayBuffer = new ArrayBuffer(0);
}

export class PhonePhoto {
  lensId: string = '';
  lensLabel: string = '';
  zoom: number = 1;
  capturedAt: number = 0;
  captureMode: string = 'SINGLE';
  shutterTimestampMs: number = 0;
  relativePath: string = '';
  bytes: number = 0;
  sha256: string = '';
}

export class CameraPhoto {
  handle: number = 0;
  filename: string = '';
  size: number = 0;
  storage: number = 0;
  parent: number = 0;
  captureDate: string = '';
  relativePath: string = '';
  sha256: string = '';
  importError: string = '';
}

export class ShotRecord {
  id: string = '';
  createdAt: number = 0;
  mode: string = 'PHONE_ONLY';
  phoneCaptureMode: string = 'SINGLE';
  expectedLensIds: string[] = [];
  phonePhotos: PhonePhoto[] = [];
  cameraPhotos: CameraPhoto[] = [];
  cameraIdentity: string = '';
  status: string = 'pending';
  error: string = '';
}

export class SessionRecord {
  schemaVersion: number = 1;
  platform: string = 'HarmonyOS';
  id: string = '';
  createdAt: number = 0;
  shots: ShotRecord[] = [];
}

export class SessionSummary {
  id: string = '';
  createdAt: number = 0;
  shotCount: number = 0;
}

export function describeError(error: Error): string {
  return error.message || String(error);
}

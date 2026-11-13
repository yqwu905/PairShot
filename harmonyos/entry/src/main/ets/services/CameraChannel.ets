import { BusinessError } from '@kit.BasicServicesKit';
import { camera } from '@kit.CameraKit';
import { image } from '@kit.ImageKit';
import { display } from '@kit.ArkUI';
import { CapturedPhoto } from '../model/Models';

/** Owns one real CameraInput/session/output, including cancellation and buffer ownership. */
export class CameraChannel {
  onPreviewLayoutChanged: ((ratio: number) => void) | undefined = undefined;
  onSessionError: ((message: string) => void) | undefined = undefined;
  private manager: camera.CameraManager;
  private input: camera.CameraInput | undefined = undefined;
  private preview: camera.PreviewOutput | undefined = undefined;
  private output: camera.PhotoOutput | undefined = undefined;
  private session: camera.PhotoSession | undefined = undefined;
  private device: camera.CameraDevice | undefined = undefined;
  private lifecycle: Promise<void> = Promise.resolve();
  private generation: number = 0;
  private running: boolean = false;
  private surfaceId: string = '';
  private lensLabel: string = '';
  private zoom: number = 1;
  private zoomRange: number[] = [1, 1];
  private previewAspectRatio: number = 3 / 4;
  private pendingResolve: ((photo: CapturedPhoto) => void) | undefined = undefined;
  private pendingReject: ((error: Error) => void) | undefined = undefined;
  private captureTimer: number = -1;
  private captureSequence: number = 0;
  private pendingPhoto: CapturedPhoto | undefined = undefined;
  private displaySubscribed: boolean = false;
  private selectedDevice: camera.CameraDevice;
  private capabilities: camera.CameraOutputCapability;
  private concurrentType: camera.CameraConcurrentType | undefined;
  private displayCallback: () => void = (): void => {
    this.updatePreviewRotation();
  };

  constructor(manager: camera.CameraManager, device: camera.CameraDevice,
    capabilities: camera.CameraOutputCapability, label: string,
    concurrentType: camera.CameraConcurrentType | undefined = undefined) {
    this.manager = manager;
    this.selectedDevice = device;
    this.capabilities = capabilities;
    this.lensLabel = label;
    this.concurrentType = concurrentType;
  }

  getCurrentLensId(): string {
    return this.running && this.device ? this.device.cameraId : '';
  }

  getZoomRange(): number[] {
    return this.zoomRange.slice();
  }

  getPreviewAspectRatio(): number {
    return this.previewAspectRatio;
  }

  setZoom(value: number): number {
    const session = this.session;
    if (!session || !this.running) {
      throw new Error('摄像头尚未就绪');
    }
    if (!Number.isFinite(value)) {
      throw new Error('变焦倍率无效');
    }
    const bounded = Math.max(this.zoomRange[0], Math.min(this.zoomRange[1], value));
    session.setZoomRatio(bounded);
    this.zoom = session.getZoomRatio();
    return this.zoom;
  }

  async startPreview(lensId: string, surfaceId: string): Promise<void> {
    if (this.running && this.device?.cameraId === lensId && this.surfaceId === surfaceId) {
      return;
    }
    if (surfaceId.length === 0) {
      throw new Error('预览画面尚未创建');
    }
    const generation = ++this.generation;
    this.running = false;
    this.rejectPending(new Error('镜头切换，拍摄已中止'));
    const operation = this.lifecycle.then(async (): Promise<void> => {
      await this.releaseResources();
      this.requireGeneration(generation);
      try {
        await this.openPreview(lensId, surfaceId, generation);
      } catch (error) {
        await this.releaseResources();
        throw error as Error;
      }
    });
    this.lifecycle = operation.catch((): void => {});
    await operation;
  }

  async stop(): Promise<void> {
    ++this.generation;
    this.running = false;
    this.rejectPending(new Error('摄像头已停止，未完成的拍摄已中止'));
    const operation = this.lifecycle.then(async (): Promise<void> => {
      await this.releaseResources();
    });
    this.lifecycle = operation.catch((): void => {});
    await operation;
  }

  capture(): Promise<CapturedPhoto> {
    const output = this.output;
    const device = this.device;
    if (!this.running || !output || !device) {
      return Promise.reject(new Error('摄像头尚未就绪，请重新打开预览'));
    }
    if (this.pendingResolve) {
      return Promise.reject(new Error('上一张照片仍在处理中'));
    }
    const generation = this.generation;
    const photo = new CapturedPhoto();
    photo.lensId = device.cameraId;
    photo.lensLabel = this.lensLabel;
    photo.zoom = this.zoom;
    // Wall-clock request time; deliberately not presented as sensor exposure time.
    photo.capturedAt = Date.now();
    photo.captureMode = this.concurrentType === undefined ? 'SINGLE' : 'CONCURRENT';
    const captureSequence = ++this.captureSequence;
    return new Promise<CapturedPhoto>((resolve, reject): void => {
      this.pendingResolve = resolve;
      this.pendingReject = reject;
      this.pendingPhoto = photo;
      this.captureTimer = setTimeout((): void => {
        if (captureSequence !== this.captureSequence || generation !== this.generation) {
          return;
        }
        this.invalidate(new Error('等待 JPEG 照片超时，请重新打开预览'), generation);
      }, 20000);
      try {
        const settings: camera.PhotoCaptureSetting = {
          quality: camera.QualityLevel.QUALITY_LEVEL_HIGH,
          rotation: output.getPhotoRotation(display.getDefaultDisplaySync().rotation * 90)
        };
        output.capture(settings).catch((error: Error): void => {
          if (captureSequence === this.captureSequence && this.pendingResolve) {
            this.invalidate(new Error(`拍摄请求失败：${error.message}`), generation);
          }
        });
      } catch (error) {
        this.invalidate(error as Error, generation);
      }
    });
  }

  private async openPreview(lensId: string, surfaceId: string, generation: number): Promise<void> {
    const device = this.selectedDevice;
    if (device.cameraId !== lensId) {
      throw new Error('选中的镜头当前不可用');
    }
    const capabilities = this.capabilities;
    const photoProfile = this.choosePhotoProfile(capabilities.photoProfiles);
    const previewProfile = this.choosePreviewProfile(capabilities.previewProfiles, photoProfile);
    const input = this.manager.createCameraInput(device);
    this.input = input;
    this.device = device;
    input.on('error', device, (error: BusinessError): void => {
      this.invalidate(new Error(`摄像头异常（${error.code}）：${error.message}`), generation);
    });
    if (this.concurrentType === undefined) {
      await input.open();
    } else {
      await input.open(this.concurrentType);
    }
    this.requireGeneration(generation);
    const preview = this.manager.createPreviewOutput(previewProfile, surfaceId);
    this.preview = preview;
    const output = this.manager.createPhotoOutput(photoProfile);
    this.output = output;
    output.on('frameShutter', (error: BusinessError, info: camera.FrameShutterInfo): void => {
      if ((!error || error.code === 0) && info && generation === this.generation && this.pendingPhoto &&
        Number.isFinite(info.timestamp) && info.timestamp > 0) {
        this.pendingPhoto.shutterTimestampMs = info.timestamp;
      }
    });
    output.on('photoAvailable', (error: BusinessError, photo: camera.Photo): void => {
      if (error && error.code !== 0) {
        this.invalidate(new Error(`获取照片失败（${error.code}）：${error.message}`), generation);
        if (photo) {
          this.releasePhoto(photo).catch((): void => {});
        }
        return;
      }
      this.receivePhoto(photo, generation).catch((failure: Error): void => {
        this.invalidate(failure, generation);
      });
    });
    output.on('error', (error: BusinessError): void => {
      this.invalidate(new Error(`照片输出异常（${error.code}）：${error.message}`), generation);
    });
    preview.on('error', (error: BusinessError): void => {
      this.invalidate(new Error(`预览异常（${error.code}）：${error.message}`), generation);
    });
    const session = this.manager.createSession<camera.PhotoSession>(camera.SceneMode.NORMAL_PHOTO);
    this.session = session;
    session.on('error', (error: BusinessError): void => {
      this.invalidate(new Error(`相机会话异常（${error.code}）：${error.message}`), generation);
    });
    session.beginConfig();
    if (!session.canAddInput(input)) {
      throw new Error('系统未允许使用此镜头，请关闭其他相机应用后重试');
    }
    session.addInput(input);
    if (!session.canAddOutput(preview)) {
      throw new Error('此镜头不支持所选预览配置');
    }
    session.addOutput(preview);
    if (!session.canAddOutput(output)) {
      throw new Error('此镜头不支持预览与 JPEG 拍摄组合');
    }
    session.addOutput(output);
    await session.commitConfig();
    this.requireGeneration(generation);
    if (session.isFocusModeSupported(camera.FocusMode.FOCUS_MODE_CONTINUOUS_AUTO)) {
      session.setFocusMode(camera.FocusMode.FOCUS_MODE_CONTINUOUS_AUTO);
    }
    if (session.isExposureModeSupported(camera.ExposureMode.EXPOSURE_MODE_CONTINUOUS_AUTO)) {
      session.setExposureMode(camera.ExposureMode.EXPOSURE_MODE_CONTINUOUS_AUTO);
    }
    await session.start();
    this.requireGeneration(generation);
    this.running = true;
    this.surfaceId = surfaceId;
    const range = session.getZoomRatioRange();
    this.zoomRange = range.length >= 2 && range[0] > 0 && range[1] >= range[0] ?
      [range[0], range[1]] : [1, 1];
    this.zoom = session.getZoomRatio();
    this.updatePreviewRotation();
    display.on('change', this.displayCallback);
    this.displaySubscribed = true;
  }

  private async receivePhoto(photo: camera.Photo, generation: number): Promise<void> {
    const sequence = this.captureSequence;
    const pending = this.pendingPhoto;
    let result: CapturedPhoto | undefined = undefined;
    try {
      if (generation === this.generation && pending && this.pendingResolve) {
        const component = await photo.main.getComponent(image.ComponentType.JPEG);
        if (component.byteBuffer.byteLength < 4) {
          throw new Error('相机返回的 JPEG 数据为空');
        }
        const bytes = new Uint8Array(component.byteBuffer);
        if (bytes[0] !== 0xFF || bytes[1] !== 0xD8) {
          throw new Error('相机没有返回有效的 JPEG 图像');
        }
        // Copy before release: the component buffer belongs to the camera image.
        pending.jpeg = component.byteBuffer.slice(0);
        result = pending;
      }
    } finally {
      await this.releasePhoto(photo);
    }
    if (result && sequence === this.captureSequence && generation === this.generation) {
      const resolve = this.pendingResolve;
      this.clearPending();
      if (resolve) {
        resolve(result);
      }
    }
  }

  private async releasePhoto(photo: camera.Photo): Promise<void> {
    try {
      await photo.main.release();
    } finally {
      await photo.release();
    }
  }

  private updatePreviewRotation(): void {
    const preview = this.preview;
    if (!preview) {
      return;
    }
    try {
      const rotation = preview.getPreviewRotation(display.getDefaultDisplaySync().rotation * 90);
      preview.setPreviewRotation(rotation, false);
      const profile = preview.getActiveProfile();
      const ratio = rotation === camera.ImageRotation.ROTATION_90 ||
        rotation === camera.ImageRotation.ROTATION_270 ? profile.size.height / profile.size.width :
        profile.size.width / profile.size.height;
      if (ratio !== this.previewAspectRatio) {
        this.previewAspectRatio = ratio;
        if (this.onPreviewLayoutChanged) {
          this.onPreviewLayoutChanged(ratio);
        }
      }
    } catch (error) {
      // Rotation must not tear down an otherwise valid camera session.
    }
  }

  private invalidate(error: Error, generation: number): void {
    if (generation !== this.generation) {
      return;
    }
    this.rejectPending(error);
    if (this.onSessionError) {
      this.onSessionError(error.message);
    }
    // A timed-out/error capture must never satisfy a later shutter request.
    this.stop().catch((): void => {});
  }

  private rejectPending(error: Error): void {
    const reject = this.pendingReject;
    this.clearPending();
    if (reject) {
      reject(error);
    }
  }

  private clearPending(): void {
    if (this.captureTimer >= 0) {
      clearTimeout(this.captureTimer);
      this.captureTimer = -1;
    }
    this.pendingResolve = undefined;
    this.pendingReject = undefined;
    this.pendingPhoto = undefined;
  }

  private requireGeneration(generation: number): void {
    if (generation !== this.generation) {
      throw new Error('摄像头初始化已取消');
    }
  }

  private async releaseResources(): Promise<void> {
    this.running = false;
    this.surfaceId = '';
    this.zoomRange = [1, 1];
    if (this.displaySubscribed) {
      try { display.off('change', this.displayCallback); } catch (error) {}
      this.displaySubscribed = false;
    }
    const session = this.session;
    const preview = this.preview;
    const output = this.output;
    const input = this.input;
    const device = this.device;
    this.session = undefined;
    this.preview = undefined;
    this.output = undefined;
    this.input = undefined;
    this.device = undefined;
    if (session) {
      try { session.off('error'); } catch (error) {}
      try { await session.stop(); } catch (error) {}
    }
    if (output) {
      try { output.off('frameShutter'); } catch (error) {}
      try { output.off('photoAvailable'); } catch (error) {}
      try { output.off('error'); } catch (error) {}
      try { await output.release(); } catch (error) {}
    }
    if (preview) {
      try { preview.off('error'); } catch (error) {}
      try { await preview.release(); } catch (error) {}
    }
    if (session) {
      try { await session.release(); } catch (error) {}
    }
    if (input) {
      if (device) {
        try { input.off('error', device); } catch (error) {}
      }
      try { await input.close(); } catch (error) {}
    }
  }

  private choosePhotoProfile(profiles: camera.Profile[]): camera.Profile {
    const jpeg = profiles.filter((profile: camera.Profile): boolean =>
      profile.format === camera.CameraFormat.CAMERA_FORMAT_JPEG);
    if (jpeg.length === 0) {
      throw new Error('此镜头未开放 JPEG 拍摄');
    }
    // Prefer ordinary 4:3 photos around 12 MP instead of a vendor-specific high-resolution mode.
    jpeg.sort((left: camera.Profile, right: camera.Profile): number =>
      this.photoScore(left) - this.photoScore(right));
    return jpeg[0];
  }

  private photoScore(profile: camera.Profile): number {
    const width = profile.size.width;
    const height = profile.size.height;
    const pixels = width * height;
    const target = this.concurrentType === undefined ? 12000000 : 2000000;
    return Math.abs(width / height - 4 / 3) * 50 + Math.abs(pixels - target) / target;
  }

  private choosePreviewProfile(profiles: camera.Profile[], photo: camera.Profile): camera.Profile {
    if (profiles.length === 0) {
      throw new Error('此镜头未开放预览');
    }
    const options = profiles.slice();
    const ratio = photo.size.width / photo.size.height;
    options.sort((left: camera.Profile, right: camera.Profile): number =>
      this.previewScore(left, ratio) - this.previewScore(right, ratio));
    return options[0];
  }

  private previewScore(profile: camera.Profile, ratio: number): number {
    const pixels = profile.size.width * profile.size.height;
    const formatPenalty = profile.format === camera.CameraFormat.CAMERA_FORMAT_YUV_420_SP ? 0 : 100;
    return Math.abs(profile.size.width / profile.size.height - ratio) * 50 +
      Math.abs(pixels - 1280 * 960) / (1280 * 960) + formatPenalty;
  }

}

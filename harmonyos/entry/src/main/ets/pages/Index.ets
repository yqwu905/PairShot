import { common } from '@kit.AbilityKit';
import { PairShotController, ViewState } from '../services/PairShotController';
import { CameraPhoto, LensInfo, ShotRecord, SessionSummary } from '../model/Models';
import { UsbCameraInfo } from '../services/PtpCamera';

@Component
struct LensPreviewSurface {
  @Prop lensId: string = '';
  @Prop label: string = '';
  @Prop imageWidth: number = 240;
  @Prop imageHeight: number = 320;
  @Prop slotHeight: number = 340;
  onReady: (lensId: string, surfaceId: string) => void = (): void => {};
  onLost: (lensId: string, surfaceId: string) => void = (): void => {};
  private surfaceController: XComponentController = new XComponentController();
  private loadedSurfaceId: string = '';

  build() {
    Stack({ alignContent: Alignment.Center }) {
      XComponent({ id: `pairshot-preview-${this.lensId}`, type: XComponentType.SURFACE,
        controller: this.surfaceController })
        .width(this.imageWidth).height(this.imageHeight)
        .onLoad(() => {
          this.loadedSurfaceId = this.surfaceController.getXComponentSurfaceId();
          this.onReady(this.lensId, this.loadedSurfaceId);
        })
        .onDestroy(() => {
          this.onLost(this.lensId, this.loadedSurfaceId);
          this.loadedSurfaceId = '';
        })
      Text(this.label).fontSize(11).fontColor('#FFFFFF').padding(6)
        .backgroundColor('#88000000').borderRadius(6).align(Alignment.BottomStart).margin(6)
    }.width('100%').height(this.slotHeight).clip(true).backgroundColor('#152128')
  }
}

@Entry
@Component
struct Index {
  @State private state: ViewState = new ViewState();
  @State private allowIncomplete: boolean = false;
  @State private showHistory: boolean = false;
  @State private previewWidth: number = 320;
  @StorageLink('pairshotActive') @Watch('onActiveChanged') private appActive: boolean = true;
  private controller: PairShotController | undefined = undefined;

  private lensLabel(lensId: string): string {
    return this.state.lenses.find((lens: LensInfo): boolean => lens.id === lensId)?.label || lensId;
  }

  private slotHeight(): number {
    return this.state.selectedLensIds.length > 1 ? 210 : 340;
  }

  private previewRatio(index: number): number {
    return this.state.previewRatios[index] || 3 / 4;
  }

  private surfaceWidth(index: number): number {
    const columns = this.state.selectedLensIds.length > 1 ? 2 : 1;
    const slotWidth = (this.previewWidth - (columns - 1) * 6) / columns;
    return Math.min(slotWidth, this.slotHeight() * this.previewRatio(index));
  }

  aboutToAppear(): void {
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.controller = new PairShotController(context, (state: ViewState): void => {
      this.state = state;
    });
    this.controller.initialize();
  }

  aboutToDisappear(): void {
    this.controller?.dispose();
  }

  private onActiveChanged(): void {
    if (this.appActive) {
      this.controller?.resume();
    } else {
      this.controller?.suspend();
    }
  }

  private shotTitle(shot: ShotRecord): string {
    const time = new Date(shot.createdAt).toLocaleTimeString();
    const mode = shot.mode === 'PHONE_ONLY' ? '手机拍摄' :
      (shot.mode === 'PHONE_TO_CAMERA' ? '手机 → 相机' : '相机 → 手机');
    return `${time} · ${mode}`;
  }

  private shotDetail(shot: ShotRecord): string {
    const imported = shot.cameraPhotos.filter((photo) => photo.relativePath.length > 0).length;
    const capture = shot.phoneCaptureMode === 'CONCURRENT' ? ' · 并发' :
      (shot.phoneCaptureMode === 'SEQUENTIAL' ? ' · 顺序' : '');
    const phone = `手机 ${shot.phonePhotos.length}/${shot.expectedLensIds.length}${capture}`;
    return shot.mode === 'PHONE_ONLY' ? phone : `${phone} · 相机原片 ${imported}/${shot.cameraPhotos.length}`;
  }

  private shotStatus(shot: ShotRecord): string {
    if (shot.status === 'complete') {
      return '已完成';
    }
    if (shot.status === 'pending') {
      return '处理中';
    }
    return '待完成';
  }

  @Builder
  modeButton(label: string, mode: string) {
    Button(label, { type: ButtonType.Normal })
      .fontSize(13)
      .fontWeight(FontWeight.Medium)
      .fontColor(this.state.mode === mode ? '#FFFFFF' : '#59666C')
      .backgroundColor(this.state.mode === mode ? '#203A40' : '#E7ECEB')
      .borderRadius(12)
      .layoutWeight(1)
      .height(40)
      .enabled(!this.state.busy && !this.state.linked)
      .onClick(() => { this.controller?.setMode(mode); })
  }

  @Builder
  header() {
    Row() {
      Column({ space: 3 }) {
        Text('PairShot').fontSize(28).fontWeight(FontWeight.Bold).fontColor('#172B31')
        Text('让每个视角，都有记录').fontSize(12).fontColor('#718087')
      }.alignItems(HorizontalAlign.Start)
      Blank()
      Text('鸿蒙版 0.2.0').fontSize(11).fontColor('#3A6D70')
        .padding({ left: 10, right: 10, top: 6, bottom: 6 })
        .backgroundColor('#E2EFED').borderRadius(12)
    }.width('100%').margin({ bottom: 18 })
    Row({ space: 6 }) {
      this.modeButton('仅手机', 'PHONE_ONLY')
      this.modeButton('手机 → 相机', 'PHONE_TO_CAMERA')
      this.modeButton('相机 → 手机', 'CAMERA_TO_PHONE')
    }.width('100%').margin({ bottom: 14 })
  }

  @Builder
  captureModeButton(label: string, mode: string) {
    Button(label, { type: ButtonType.Normal }).fontSize(12).height(34).layoutWeight(1).borderRadius(9)
      .backgroundColor(this.state.captureMode === mode ? '#DAEBE7' : '#2D4248')
      .fontColor(this.state.captureMode === mode ? '#183B3C' : '#D0DDDE')
      .enabled(!this.state.busy && !this.state.linked)
      .onClick(() => { this.controller?.setCaptureMode(mode); })
  }

  @Builder
  preview() {
    Column({ space: 0 }) {
      Stack({ alignContent: Alignment.TopEnd }) {
        if (this.state.permissionGranted && this.state.selectedLensIds.length > 0) {
          Grid() {
            ForEach(this.state.selectedLensIds, (lensId: string, index: number) => {
              GridItem() {
                LensPreviewSurface({ lensId: lensId, label: this.lensLabel(lensId),
                  imageWidth: this.surfaceWidth(index), imageHeight: this.surfaceWidth(index) / this.previewRatio(index),
                  slotHeight: this.slotHeight(),
                  onReady: (id: string, surfaceId: string): void => { this.controller?.attachLensSurface(id, surfaceId); },
                  onLost: (id: string, surfaceId: string): void => { this.controller?.detachLensSurface(id, surfaceId); } })
              }
            }, (lensId: string) => lensId)
          }.columnsTemplate(this.state.selectedLensIds.length > 1 ? '1fr 1fr' : '1fr')
            .columnsGap(6).rowsGap(6).width('100%')
            .height(this.slotHeight() * Math.ceil(this.state.selectedLensIds.length /
              (this.state.selectedLensIds.length > 1 ? 2 : 1)) + (this.state.selectedLensIds.length > 2 ? 6 : 0))
            .onAreaChange((_old: Area, area: Area) => {
              const width = Number(area.width);
              if (width > 0 && Math.abs(width - this.previewWidth) > 1) {
                this.previewWidth = width;
              }
            })
        } else if (this.state.permissionGranted) {
          Text('请选择手机镜头').fontSize(16).fontColor('#AAB8BD').height(220).width('100%')
            .textAlign(TextAlign.Center)
        } else {
          Column({ space: 14 }) {
            Text('开启你的第一个视角').fontSize(20).fontColor('#FFFFFF').fontWeight(FontWeight.Medium)
            Text('允许摄像头访问后，即可预览和拍摄').fontSize(13).fontColor('#AAB8BD')
            Button('授权摄像头').fontColor('#172B31').backgroundColor('#E8F1EE')
              .onClick(() => { this.controller?.grantPermission(); })
          }.width('100%').height(280).justifyContent(FlexAlign.Center)
        }
        Text(`${this.state.zoom.toFixed(1)}×`).fontSize(12).fontColor('#FFFFFF')
          .padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .backgroundColor('#88000000').borderRadius(14).margin(12)
      }.width('100%').backgroundColor('#152128')

      Column({ space: 10 }) {
        Row() {
          Text('选择镜头').fontSize(13).fontColor('#C6D5D7')
          Blank()
          Text(this.state.selectedLensIds.length > 1 ? `${this.state.selectedLensIds.length} 镜头 · ` +
            (this.state.captureMode === 'CONCURRENT' ? '并发拍摄' : '顺序拍摄') : '单镜头拍摄')
            .fontSize(11).fontColor('#8EA9AA')
        }.width('100%')
        if (this.state.lenses.length > 0) {
          Scroll() {
            Row({ space: 8 }) {
              ForEach(this.state.lenses, (lens: LensInfo) => {
                Button(lens.label, { type: ButtonType.Normal })
                  .fontSize(12).height(34).borderRadius(10)
                  .backgroundColor(this.state.selectedLensIds.includes(lens.id) ? '#DAEBE7' : '#2D4248')
                  .fontColor(this.state.selectedLensIds.includes(lens.id) ? '#183B3C' : '#D0DDDE')
                  .enabled(!this.state.busy && !this.state.linked)
                  .onClick(() => { this.controller?.selectLens(lens.id); })
              }, (lens: LensInfo) => lens.id)
            }
          }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%')
        } else {
          Text('授权后显示系统向应用开放的镜头').fontSize(12).fontColor('#8EA9AA')
        }
        Row({ space: 6 }) {
          Text(this.state.cameraSummary).fontSize(11).fontColor('#8EA9AA').layoutWeight(1)
          Button('刷新', { type: ButtonType.Normal }).fontSize(11).height(30).borderRadius(8)
            .fontColor('#D0DDDE').backgroundColor('#2D4248').enabled(!this.state.busy && !this.state.linked)
            .onClick(() => { this.controller?.refreshCameras(); })
          Button('复制镜头诊断', { type: ButtonType.Normal }).fontSize(11).height(30).borderRadius(8)
            .fontColor('#D0DDDE').backgroundColor('#2D4248').enabled(!this.state.busy)
            .onClick(() => { this.controller?.copyCameraDiagnostics(); })
        }.width('100%')
        if (this.state.selectedLensIds.length > 1) {
          Row({ space: 6 }) {
            this.captureModeButton('并发拍照', 'CONCURRENT')
            this.captureModeButton('顺序拍照', 'SEQUENTIAL')
          }.width('100%')
          Text(this.state.captureMode === 'SEQUENTIAL' ? '按镜头顺序逐个拍摄，归为同一组。' :
            (this.state.concurrentReason || (this.state.previewReady ? '多路会话就绪；并发提交快门，不承诺同时曝光。' :
              '正在准备多路会话；全部就绪后才能拍摄。')))
            .fontSize(12).fontColor(this.state.concurrentReason && this.state.captureMode === 'CONCURRENT' ? '#F2B798' : '#8EA9AA')
            .width('100%').lineHeight(17)
        }
        if (this.state.maxZoom > this.state.minZoom) {
          Row({ space: 8 }) {
            Text(`${this.state.minZoom.toFixed(1)}×`).fontSize(11).fontColor('#B8CDCE')
            Slider({ value: this.state.zoom, min: this.state.minZoom, max: this.state.maxZoom, step: 0.1 })
              .layoutWeight(1).selectedColor('#80BDB6').blockColor('#E7F2EF')
              .enabled(!this.state.busy)
              .onChange((value: number) => { this.controller?.setZoom(value); })
            Text(`${this.state.maxZoom.toFixed(1)}×`).fontSize(11).fontColor('#B8CDCE')
          }
        }
        Button(this.state.busy ? '正在处理…' :
          (this.state.mode === 'PHONE_ONLY' ?
            (this.state.selectedLensIds.length > 1 ? (this.state.captureMode === 'CONCURRENT' ? '并发拍照' : '顺序拍照') : '手机拍照') :
            (this.state.mode === 'PHONE_TO_CAMERA' ? '联动拍照' : '相机快门触发跟拍')),
          { type: ButtonType.Normal })
          .width('100%').height(50).borderRadius(14).fontSize(17).fontWeight(FontWeight.Bold)
          .fontColor('#FFFFFF').backgroundColor('#D96C43')
          .enabled(!this.state.busy && this.state.permissionGranted && this.state.previewReady && this.state.selectedLensIds.length > 0 &&
            (this.state.mode === 'PHONE_ONLY' || (this.state.mode === 'PHONE_TO_CAMERA' && this.state.linked)))
          .onClick(() => { this.controller?.capture(); })
      }.width('100%').padding(14).backgroundColor('#1B3036')
    }.width('100%').borderRadius(18).clip(true)
  }

  @Builder
  connection() {
    Column({ space: 10 }) {
      Row() {
        Text('USB 相机').fontSize(17).fontWeight(FontWeight.Bold).fontColor('#203A40')
        Blank()
        Button('刷新', { type: ButtonType.Normal }).fontSize(12).height(30)
          .backgroundColor('#E8EFED').fontColor('#365C5C').borderRadius(8)
          .enabled(!this.state.busy && !this.state.linked)
          .onClick(() => { this.controller?.refreshUsb(); })
      }.width('100%')
      if (this.state.cameraConnected) {
        Text(this.state.cameraLabel).fontSize(14).fontColor('#2A6B62').width('100%')
        Row({ space: 8 }) {
          Button(this.state.linked ? '停止联动' : '开启联动', { type: ButtonType.Normal })
            .layoutWeight(1).height(40).borderRadius(10).backgroundColor('#315C5B').fontColor('#FFFFFF')
            .enabled(!this.state.busy)
            .onClick(() => {
              if (this.state.linked) {
                this.controller?.stopLink();
              } else {
                this.controller?.startLink();
              }
            })
          Button('断开', { type: ButtonType.Normal })
            .height(40).borderRadius(10).backgroundColor('#EEF1EF').fontColor('#53686B')
            .enabled(!this.state.busy)
            .onClick(() => { this.controller?.disconnectUsb(); })
        }
      } else {
        if (this.state.usbDevices.length === 0) {
          Text('通过 OTG 数据线连接相机，切换到 PTP 模式后刷新。')
            .fontSize(13).fontColor('#718087').width('100%')
        }
        ForEach(this.state.usbDevices, (device: UsbCameraInfo) => {
          Button(`连接并授权 · ${device.label}`, { type: ButtonType.Normal })
            .fontSize(13).width('100%').borderRadius(10).height(40)
            .backgroundColor('#E2EFEB').fontColor('#305A53')
            .enabled(!this.state.busy)
            .onClick(() => { this.controller?.connectUsb(device.name); })
        }, (device: UsbCameraInfo) => device.name)
      }
      Text(this.state.mode === 'CAMERA_TO_PHONE' ?
        '相机生成新照片后，手机延迟跟拍；不代表同时曝光。' :
        '软件提交两端拍摄请求；手机镜头按所选方式拍摄，不保证同时曝光。')
        .fontSize(12).fontColor('#8C7966').width('100%').lineHeight(18)
    }.width('100%').padding(16).backgroundColor('#FFFFFF').borderRadius(16).margin({ top: 14 })
  }

  @Builder
  records() {
    Column({ space: 12 }) {
      Row() {
        Text('拍摄记录').fontSize(18).fontWeight(FontWeight.Bold).fontColor('#203A40')
        Text(`  ${this.state.shots.length} 组`).fontSize(12).fontColor('#7C8D91')
        Blank()
        Button('历史', { type: ButtonType.Normal }).fontSize(12).height(30)
          .backgroundColor('#E8EFED').fontColor('#365C5C').borderRadius(8)
          .enabled(!this.state.busy && !this.state.linked)
          .onClick(() => { this.showHistory = !this.showHistory; })
        Button('新建', { type: ButtonType.Normal }).fontSize(12).height(30).margin({ left: 6 })
          .backgroundColor('#E8EFED').fontColor('#365C5C').borderRadius(8)
          .enabled(!this.state.busy && !this.state.linked)
          .onClick(() => { this.controller?.newSession(); })
      }.width('100%')
      if (this.showHistory) {
        ForEach(this.state.history, (session: SessionSummary) => {
          Button(`${new Date(session.createdAt).toLocaleString()} · ${session.shotCount} 组`,
            { type: ButtonType.Normal })
            .fontSize(12).width('100%').height(36).borderRadius(8)
            .backgroundColor(session.id === this.state.sessionId ? '#D9EAE4' : '#F0F3F1')
            .fontColor('#365C5C').enabled(!this.state.busy && !this.state.linked)
            .onClick(() => { this.controller?.loadSession(session.id); this.showHistory = false; })
        }, (session: SessionSummary) => session.id)
      }
      if (this.state.shots.length === 0) {
        Column({ space: 6 }) {
          Text('第一张，从这里开始').fontSize(15).fontColor('#6A7F83')
          Text('照片保存在应用内，可按组导出 ZIP').fontSize(12).fontColor('#94A2A4')
        }.width('100%').padding({ top: 22, bottom: 22 })
      }
      ForEach(this.state.shots.slice().reverse().slice(0, 20), (shot: ShotRecord) => {
        Column({ space: 6 }) {
          Row() {
            Text(this.shotTitle(shot)).fontSize(13).fontWeight(FontWeight.Medium).fontColor('#2C464B')
            Blank()
            Text(this.shotStatus(shot)).fontSize(11)
              .fontColor(shot.status === 'complete' ? '#2E8071' : '#A57242')
          }.width('100%')
          Text(this.shotDetail(shot)).fontSize(12).fontColor('#7C8D91').width('100%')
          if (shot.error.length > 0) {
            Text(shot.error).fontSize(12).fontColor('#A4533B').width('100%')
          }
          ForEach(shot.cameraPhotos.filter((photo: CameraPhoto) => photo.importError.length > 0),
            (photo: CameraPhoto) => {
              Text(`${photo.filename}：${photo.importError}`)
                .fontSize(12).fontColor('#A4533B').width('100%')
            }, (photo: CameraPhoto) => photo.handle.toString())
        }.padding(12).width('100%').backgroundColor('#F4F6F3').borderRadius(10)
      }, (shot: ShotRecord) => shot.id)
      if (this.state.shots.length > 20) {
        Text('显示最近 20 组；导出包含本批次全部记录。').fontSize(11).fontColor('#7C8D91')
      }
      if (this.state.mode !== 'PHONE_ONLY') {
        Button('复制本批次相机原片', { type: ButtonType.Normal })
          .width('100%').height(42).borderRadius(10).fontSize(14)
          .backgroundColor('#E4EEEA').fontColor('#2C5E54')
          .enabled(!this.state.busy && this.state.cameraConnected && this.state.shots.length > 0)
          .onClick(() => { this.controller?.importPhotos(); })
      }
      Row({ space: 4 }) {
        Checkbox({ name: 'incomplete' }).select(this.allowIncomplete).selectedColor('#527C72')
          .onChange((value: boolean) => { this.allowIncomplete = value; })
        Text('允许导出不完整记录').fontSize(12).fontColor('#73888C')
        Blank()
      }.width('100%')
      Button('打包照片 ZIP', { type: ButtonType.Normal })
        .width('100%').height(46).borderRadius(12).fontSize(15)
        .backgroundColor('#2F5559').fontColor('#FFFFFF')
        .enabled(!this.state.busy && !this.state.linked && this.state.shots.length > 0)
        .onClick(() => { this.controller?.exportZip(this.allowIncomplete); })
      if (this.state.lastExport.length > 0) {
        Text('ZIP 已保存到你选择的位置').fontSize(12).fontColor('#2E8071')
      }
    }.width('100%').padding(16).backgroundColor('#FFFFFF').borderRadius(16).margin({ top: 14 })
  }

  build() {
    Scroll() {
      Column({ space: 0 }) {
        this.header()
        this.preview()
        if (this.state.status.length > 0) {
          Text(this.state.status).fontSize(12).fontColor('#5F7A7D').width('100%')
            .lineHeight(18).margin({ top: 10 })
        }
        if (this.state.error.length > 0) {
          Text(this.state.error).fontSize(13).fontColor('#A34F36').width('100%')
            .padding(12).backgroundColor('#F9EAE0').borderRadius(10).margin({ top: 8 })
        }
        if (this.state.mode !== 'PHONE_ONLY') {
          this.connection()
        }
        this.records()
        Text('系统开放的镜头与 USB 能力取决于设备。卸载前请导出照片。')
          .fontSize(11).fontColor('#8A9A9C').lineHeight(17).textAlign(TextAlign.Center)
          .margin({ top: 18, bottom: 24 })
      }.width('100%').constraintSize({ maxWidth: 640 }).padding({ left: 18, right: 18, top: 22 })
    }.width('100%').height('100%').backgroundColor('#F2F4EF').scrollBar(BarState.Off)
  }
}

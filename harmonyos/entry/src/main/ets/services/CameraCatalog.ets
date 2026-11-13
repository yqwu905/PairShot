import { camera } from '@kit.CameraKit';
import { LensInfo, MAX_PHONE_LENSES } from '../model/Models';

// Public CameraKit additions, feature-detected on the running OS. These exact
// signatures are documented by Huawei (getCameraDevices API 23, constituents API 24).
// Keeping them in a compatibility interface preserves the API 18 minimum OS;
// no camera IDs, profiles, or physical devices are synthesized.
interface ExtendedCameraManager extends camera.CameraManager {
  getCameraDevices?(position: camera.CameraPosition, types: camera.CameraType[],
    connectType: camera.ConnectionType): camera.CameraDevice[];
}

interface ExtendedCameraDevice extends camera.CameraDevice {
  isLogicalCamera?: boolean;
  constituentCameraDevices?: camera.CameraDevice[];
}

class CatalogEntry {
  device: camera.CameraDevice;
  source: string;
  physical: boolean;
  parentId: string;
  reason: string = '';
  usable: boolean = false;
  logical: boolean = false;

  constructor(device: camera.CameraDevice, source: string, physical: boolean, parentId: string) {
    this.device = device;
    this.source = source;
    this.physical = physical;
    this.parentId = parentId;
  }
}

export class ConcurrentDevicePlan {
  device: camera.CameraDevice;
  type: camera.CameraConcurrentType;
  capabilities: camera.CameraOutputCapability;

  constructor(device: camera.CameraDevice, type: camera.CameraConcurrentType,
    capabilities: camera.CameraOutputCapability) {
    this.device = device;
    this.type = type;
    this.capabilities = capabilities;
  }
}

export class CameraCatalog {
  private manager: camera.CameraManager;
  private entries: CatalogEntry[] = [];
  private lenses: LensInfo[] = [];
  private discovery: string[] = [];
  private events: string[] = [];

  constructor(manager: camera.CameraManager) {
    this.manager = manager;
  }

  refresh(): LensInfo[] {
    this.entries = [];
    this.lenses = [];
    this.discovery = [];
    try {
      const basic = this.manager.getSupportedCameras();
      this.discovery.push(`基础查询：${basic.length} 路`);
      for (const device of basic) {
        this.remember(device, '基础查询');
      }
    } catch (error) {
      this.discovery.push('基础查询失败：' + (error as Error).message);
    }
    const positions: camera.CameraPosition[] = [camera.CameraPosition.CAMERA_POSITION_BACK,
      camera.CameraPosition.CAMERA_POSITION_FRONT];
    const types: camera.CameraType[] = [camera.CameraType.CAMERA_TYPE_DEFAULT,
      camera.CameraType.CAMERA_TYPE_WIDE_ANGLE, camera.CameraType.CAMERA_TYPE_ULTRA_WIDE,
      camera.CameraType.CAMERA_TYPE_TELEPHOTO, camera.CameraType.CAMERA_TYPE_TRUE_DEPTH];
    const extended = this.manager as ExtendedCameraManager;
    this.discovery.push('扩展查询 getCameraDevices：' +
      (typeof extended.getCameraDevices === 'function' ? '可用' : '运行系统未提供'));
    for (const position of positions) {
      if (typeof extended.getCameraDevices === 'function') {
        try {
          const devices = extended.getCameraDevices(position, types, camera.ConnectionType.CAMERA_CONNECTION_BUILT_IN);
          this.discovery.push(`扩展查询 position=${position}：${devices.length} 路`);
          for (const device of devices) {
            this.remember(device, '扩展查询');
          }
        } catch (error) {
          this.discovery.push(`扩展查询 position=${position} 失败：${(error as Error).message}`);
        }
      }
      if (typeof this.manager.getCameraDevice === 'function') {
        for (const type of types) {
          try {
            const device = this.manager.getCameraDevice(position, type);
            this.discovery.push(`类型查询 position=${position}, type=${type}：${device ? device.cameraId : '无返回设备'}`);
            if (device) {
              this.remember(device, '按类型查询');
            }
          } catch (error) {
            this.discovery.push(`类型查询 position=${position}, type=${type}：${(error as Error).message}`);
          }
        }
      }
    }
    for (let index = 0; index < this.entries.length; index++) {
      const entry = this.entries[index];
      try {
        if (!this.manager.getSupportedSceneModes(entry.device).includes(camera.SceneMode.NORMAL_PHOTO)) {
          entry.reason = '未开放普通拍照模式';
          continue;
        }
        const caps = this.manager.getSupportedOutputCapability(entry.device, camera.SceneMode.NORMAL_PHOTO);
        if (!caps.photoProfiles.some((profile: camera.Profile): boolean =>
          profile.format === camera.CameraFormat.CAMERA_FORMAT_JPEG)) {
          entry.reason = '未开放 JPEG 照片输出';
          continue;
        }
        if (caps.previewProfiles.length === 0) {
          entry.reason = '未开放预览输出';
          continue;
        }
        entry.usable = true;
        const lens = new LensInfo();
        lens.id = entry.device.cameraId;
        lens.label = this.label(entry.device) + (entry.logical ? ' · 逻辑' : (entry.physical ? ' · 物理' : '')) + ` ${index + 1}`;
        lens.position = entry.device.cameraPosition;
        lens.cameraType = entry.device.cameraType;
        lens.source = entry.source;
        lens.physical = entry.physical;
        lens.logical = entry.logical;
        lens.parentId = entry.parentId;
        this.lenses.push(lens);
      } catch (error) {
        entry.reason = '能力查询失败：' + (error as Error).message;
      }
    }
    return this.lenses.slice();
  }

  private remember(device: camera.CameraDevice | undefined, source: string,
    physical: boolean = false, parentId: string = '', depth: number = 0): void {
    if (!device || !device.cameraId || depth > 2) {
      return;
    }
    const prior = this.entries.find((entry: CatalogEntry): boolean => entry.device.cameraId === device.cameraId);
    if (prior) {
      if (!prior.source.includes(source)) {
        prior.source += '、' + source;
      }
      if (physical && parentId !== device.cameraId) {
        prior.physical = true;
        prior.parentId = parentId;
      }
    } else {
      this.entries.push(new CatalogEntry(device, source, physical, parentId));
    }
    try {
      const extended = device as ExtendedCameraDevice;
      const children = extended.constituentCameraDevices;
      const entry = this.entries.find((item: CatalogEntry): boolean => item.device.cameraId === device.cameraId);
      if (entry && (extended.isLogicalCamera === true || (children && children.length > 0))) {
        entry.logical = true;
      }
      if (children && Array.isArray(children)) {
        for (const child of children) {
          if (child.cameraId !== device.cameraId) {
            this.remember(child, '物理组成查询', true, device.cameraId, depth + 1);
          }
        }
      }
    } catch (error) {
      this.discovery.push(`物理组成查询 ${device.cameraId}：${(error as Error).message}`);
    }
  }

  getDevice(id: string): camera.CameraDevice {
    const entry = this.entries.find((item: CatalogEntry): boolean => item.device.cameraId === id && item.usable);
    if (!entry) {
      throw new Error('所选镜头未提供可用的拍照能力，请刷新镜头并复制诊断信息');
    }
    return entry.device;
  }

  label(device: camera.CameraDevice): string {
    const position = device.cameraPosition === camera.CameraPosition.CAMERA_POSITION_BACK ? '后置' :
      (device.cameraPosition === camera.CameraPosition.CAMERA_POSITION_FRONT ? '前置' : '外接');
    let type = '默认相机';
    if (device.cameraType === camera.CameraType.CAMERA_TYPE_WIDE_ANGLE) {
      type = '广角';
    } else if (device.cameraType === camera.CameraType.CAMERA_TYPE_ULTRA_WIDE) {
      type = '超广角';
    } else if (device.cameraType === camera.CameraType.CAMERA_TYPE_TELEPHOTO) {
      type = '长焦';
    } else if (device.cameraType === camera.CameraType.CAMERA_TYPE_TRUE_DEPTH) {
      type = '景深';
    }
    return position + type;
  }

  planConcurrent(ids: string[]): ConcurrentDevicePlan[] {
    if (ids.length < 2 || ids.length > MAX_PHONE_LENSES || new Set<string>(ids).size !== ids.length) {
      throw new Error(`并发拍摄请选择 2 至 ${MAX_PHONE_LENSES} 枚不同镜头`);
    }
    const devices = ids.map((id: string): camera.CameraDevice => this.getDevice(id));
    if (typeof this.manager.getCameraConcurrentInfos !== 'function') {
      throw new Error('系统未提供多摄并发能力查询接口');
    }
    const infos = this.manager.getCameraConcurrentInfos(devices);
    if (!infos || infos.length !== ids.length) {
      throw new Error('系统未声明所选镜头组合支持并发。可更换组合，或手动选择顺序拍摄');
    }
    const plans: ConcurrentDevicePlan[] = [];
    for (const device of devices) {
      const matches = infos.filter((info: camera.CameraConcurrentInfo): boolean =>
        info.device.cameraId === device.cameraId);
      if (matches.length !== 1) {
        throw new Error('系统返回的并发设备与所选物理镜头不一致，未替换镜头进行拍摄');
      }
      const info = matches[0];
      const modeIndex = info.modes.indexOf(camera.SceneMode.NORMAL_PHOTO);
      const caps = modeIndex >= 0 ? info.outputCapabilities[modeIndex] : undefined;
      if (!caps || caps.previewProfiles.length === 0 || !caps.photoProfiles.some((profile: camera.Profile): boolean =>
        profile.format === camera.CameraFormat.CAMERA_FORMAT_JPEG)) {
        throw new Error(`${this.label(device)}：系统未声明此组合的 JPEG 并发拍照能力（多摄预览/录像不等于同时拍照）`);
      }
      if (info.type !== camera.CameraConcurrentType.CAMERA_LIMITED_CAPABILITY &&
        info.type !== camera.CameraConcurrentType.CAMERA_FULL_CAPABILITY) {
        throw new Error('系统返回未知的多摄打开模式');
      }
      plans.push(new ConcurrentDevicePlan(device, info.type, caps));
    }
    this.record(`并发能力：${ids.join(', ')}；JPEG 可用；打开模式 ${plans.map((plan: ConcurrentDevicePlan): string => plan.type.toString()).join(',')}`);
    return plans;
  }

  summary(): string {
    return `发现 ${this.entries.length} 路相机，可拍照 ${this.lenses.length} 路`;
  }

  record(message: string): void {
    this.events.push(message);
    if (this.events.length > 60) {
      this.events.shift();
    }
  }

  report(): string {
    const lines = ['PairShot CameraKit 镜头诊断', this.summary()].concat(this.discovery);
    for (const entry of this.entries) {
      lines.push(`id=${entry.device.cameraId} position=${entry.device.cameraPosition} type=${entry.device.cameraType}` +
        ` source=${entry.source} physical=${entry.physical} logical=${entry.logical} parent=${entry.parentId || '-'} ` +
        (entry.usable ? '可拍照' : '排除：' + entry.reason));
    }
    return lines.concat(this.events).join('\n');
  }
}

import { UIAbility } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';

export default class EntryAbility extends UIAbility {
  onWindowStageCreate(windowStage: window.WindowStage): void {
    AppStorage.setOrCreate('pairshotActive', true);
    windowStage.loadContent('pages/Index');
  }

  onForeground(): void {
    AppStorage.setOrCreate('pairshotActive', true);
  }

  onBackground(): void {
    AppStorage.setOrCreate('pairshotActive', false);
  }
}

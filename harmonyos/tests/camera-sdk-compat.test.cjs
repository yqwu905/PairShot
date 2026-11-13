// Compile the actual compatibility declarations against CameraKit API shapes.
// Runtime mocks alone cannot catch a newer SDK making an inherited member required.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');

const toolsHome = process.env.DEVECO_HOME || '/workspace/.harmony-environment/command-line-tools';
const ts = require(path.join(toolsHome, 'codelinter/node_modules/typescript'));
const catalog = fs.readFileSync(path.resolve(__dirname, '../entry/src/main/ets/services/CameraCatalog.ets'), 'utf8');
const parsed = ts.createSourceFile('CameraCatalog.ts', catalog, ts.ScriptTarget.ES2021, true);
const declarations = parsed.statements.filter(node => ts.isInterfaceDeclaration(node) &&
  ['ExtendedCameraManager', 'ExtendedCameraDevice'].includes(node.name.text)).map(node => node.getText(parsed)).join('\n');
assert.equal(parsed.statements.filter(node => ts.isInterfaceDeclaration(node) &&
  ['ExtendedCameraManager', 'ExtendedCameraDevice'].includes(node.name.text)).length, 2);

function checkSdkShape({ managerMethod = '', deviceProperties = '' } = {}) {
  const code = `declare namespace camera {
    enum CameraPosition { BACK = 1, FRONT = 2 }
    enum CameraType { DEFAULT = 0, TELEPHOTO = 3 }
    enum ConnectionType { BUILT_IN = 0 }
    interface CameraDevice { readonly cameraId: string; ${deviceProperties} }
    interface CameraManager { getSupportedCameras(): CameraDevice[]; ${managerMethod} }
  }
  ${declarations}`;
  const filename = path.resolve(__dirname, '../../../work/camera-sdk-compat.ts');
  const options = { strict: true, noEmit: true, target: ts.ScriptTarget.ES2021, types: [] };
  const host = ts.createCompilerHost(options);
  const getSourceFile = host.getSourceFile.bind(host);
  host.getSourceFile = (file, languageVersion, onError, shouldCreateNewSourceFile) =>
    path.resolve(file) === filename ? ts.createSourceFile(file, code, languageVersion, true) :
      getSourceFile(file, languageVersion, onError, shouldCreateNewSourceFile);
  const program = ts.createProgram([filename], options, host);
  const diagnostics = ts.getPreEmitDiagnostics(program).map(diagnostic =>
    ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n'));
  assert.deepEqual(diagnostics, []);
}

const requiredMethod = 'getCameraDevices(position: CameraPosition, types: CameraType[], connectType: ConnectionType): CameraDevice[];';

test('compatibility declarations compile when the older SDK lacks the extra camera APIs', () => {
  checkSdkShape();
});

test('compatibility declarations preserve a newer SDK required getCameraDevices method', () => {
  checkSdkShape({ managerMethod: requiredMethod });
});

test('physical-camera declarations accept SDKs with readonly optional or required properties', () => {
  for (const optional of ['?', '']) {
    checkSdkShape({ managerMethod: requiredMethod,
      deviceProperties: `readonly isLogicalCamera${optional}: boolean; readonly constituentCameraDevices${optional}: CameraDevice[];` });
  }
});

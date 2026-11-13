// Same-origin WebAssembly runtime; input image bytes never leave this worker.
let runtime=null, initializing=null, busy=false, activeId=null;
const ENGINE_VERSION='color-v2';
function progress(stage,label,value,id=activeId){
  self.postMessage({type:'progress',id,stage,label,progress:value});
}
async function initialize(){
  if(runtime)return runtime;
  if(initializing)return initializing;
  initializing=(async()=>{
    progress('runtime','加载本机计算环境',2);
    const {loadPyodide}=await import('./runtime/pyodide.mjs');
    const p=await loadPyodide({indexURL:new URL('./runtime/',self.location.href).href});
    progress('runtime','加载图像配准组件',5);
    await p.loadPackage(['numpy','pillow','opencv-python'],{
      messageCallback:()=>{},errorCallback:message=>console.warn(message)
    });
    // Keep algorithm modules at one cache revision while retaining the large
    // immutable WASM runtime cache. All modules must arrive before import.
    const modules=await Promise.all(['color_global.py','color_local.py','engine.py'].map(async name=>{
      const url=new URL('./'+name,self.location.href);url.searchParams.set('v',ENGINE_VERSION);
      const response=await fetch(url);
      if(!response.ok)throw new Error('无法加载配准算法文件：'+name);
      return [name,await response.text()];
    }));
    for(const [name,source]of modules)p.FS.writeFile('/'+name,source);
    p.runPython("import sys\nsys.path.insert(0, '/')\nimport engine");
    p.globals.set('_browser_progress',progress);
    p.runPython("engine._progress = _browser_progress");
    runtime=p;
    progress('runtime','计算环境已就绪',9);
    return p;
  })();
  try{return await initializing;}catch(error){initializing=null;throw error;}
}
self.onmessage=async({data})=>{
  if(data?.type==='init'){
    try{await initialize();self.postMessage({type:'ready',id:data.id??null});}
    catch(error){self.postMessage({type:'error',id:data.id??null,code:'RUNTIME_LOAD',message:'离线计算环境加载失败。请重新打开工具页；若仍失败，请更新 Android System WebView 后重试。',detail:String(error.message||error)});}
    return;
  }
  if(data?.type!=='run')return;
  if(busy){self.postMessage({type:'error',id:data.id,code:'BUSY',message:'已有计算正在运行。'});return;}
  busy=true;activeId=data.id;
  try{
    if(!(data.main instanceof ArrayBuffer)||!(data.tele instanceof ArrayBuffer))throw new Error('请先选择主摄和长焦两张图片。');
    if(data.main.byteLength>30*1024*1024||data.tele.byteLength>30*1024*1024)throw new Error('每张图片不能超过30 MB，请先压缩后再试。');
    const p=await initialize();
    p.FS.writeFile('/input-main',new Uint8Array(data.main));
    p.FS.writeFile('/input-tele',new Uint8Array(data.tele));
    p.globals.set('_options_json',JSON.stringify(data.options||{}));
    const raw=await p.runPythonAsync("engine.run_browser('/input-main','/input-tele',_options_json)");
    const parsed=JSON.parse(raw),images={},transfers=[];
    for(const [name,path]of Object.entries(parsed.paths)){
      const bytes=p.FS.readFile(path);
      const buffer=bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength);
      images[name]=buffer;transfers.push(buffer);p.FS.unlink(path);
    }
    p.FS.unlink('/input-main');p.FS.unlink('/input-tele');
    p.runPython('import gc; gc.collect()');
    self.postMessage({type:'result',id:data.id,images,metrics:parsed.metrics},transfers);
  }catch(error){
    const detail=String(error.message||error);
    const match=detail.match(/(?:ValueError|RuntimeError|OSError): ([^\n]+)/);
    const runtimeFailure=!runtime;
    self.postMessage({type:'error',id:data.id,code:runtimeFailure?'RUNTIME_LOAD':'COMPUTE_FAILED',message:runtimeFailure?'无法启动离线计算引擎。请重新打开工具页；若仍失败，请更新 Android System WebView 后重试。':match?match[1]:'配准未完成。请确认两图拍摄相同场景，或降低计算分辨率后重试。',detail});
    if(runtime){for(const path of ['/input-main','/input-tele']){try{runtime.FS.unlink(path);}catch{}}}
  }finally{busy=false;activeId=null;}
};

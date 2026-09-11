const path=require('path'),fs=require('fs');
const {createCanvas,loadImage}=require(path.join(process.env.HOME,'.dsh/profiles/web/node_modules/@napi-rs/canvas'));
const OUT='icon-out'; fs.mkdirSync(OUT,{recursive:true});

const clamp=(v,a,b)=>v<a?a:v>b?b:v;

(async()=>{
  const img=await loadImage('./ref-image.png');
  const W=img.width,H=img.height;
  const cv=createCanvas(W,H),cx=cv.getContext('2d');
  cx.drawImage(img,0,0);
  const id=cx.getImageData(0,0,W,H), d=id.data;
  const N=W*H;
  const gi=(x,y)=>(y*W+x)*4;

  // ---------- 1) 洪水填充：从画布边框向内吃掉“白底” ----------
  const whiteM=new Uint8Array(N);
  const isWhiteish=(i)=> d[i]>235 && d[i+1]>235 && d[i+2]>235;
  const stack=[];
  for(let x=0;x<W;x++){ stack.push(x,y_=0); stack.push(x,H-1); }
  for(let y=0;y<H;y++){ stack.push(0,y); stack.push(W-1,y); }
  while(stack.length){
    const y=stack.pop(), x=stack.pop();
    const p=y*W+x; if(whiteM[p]) continue;
    const i=p*4; if(!isWhiteish(i)) continue;
    whiteM[p]=1;
    if(x>0)stack.push(x-1,y); if(x<W-1)stack.push(x+1,y);
    if(y>0)stack.push(x,y-1); if(y<H-1)stack.push(x,y+1);
  }
  let whiteCount=0; for(let p=0;p<N;p++) whiteCount+=whiteM[p];
  console.log('白底像素: '+whiteCount+' ('+(whiteCount/N*100).toFixed(1)+'%)');

  // ---------- 2) 洪水填充：从“绿底”边框向内吃掉绿色背景（保护气泡内的绿字/字母） ----------
  const greenM=new Uint8Array(N);
  const isGreenish=(i)=>{ const r=d[i],g=d[i+1],b=d[i+2]; return (g-r)>40 && (g-b)>20; };
  const st2=[];
  // 起点：紧贴白底边界的绿像素（绿块被白边包住，不接触画布边框）
  const touchWhite=(x,y)=>{
    if(x>0&&whiteM[y*W+x-1])return true;
    if(x<W-1&&whiteM[y*W+x+1])return true;
    if(y>0&&whiteM[(y-1)*W+x])return true;
    if(y<H-1&&whiteM[(y+1)*W+x])return true;
    return false;
  };
  for(let y=0;y<H;y++)for(let x=0;x<W;x++){
    const p=y*W+x;
    if(!whiteM[p] && isGreenish(p*4) && touchWhite(x,y)) st2.push(x,y);
  }
  console.log('绿底种子点: '+(st2.length/2));
  while(st2.length){
    const y=st2.pop(), x=st2.pop();
    const p=y*W+x; if(greenM[p]||whiteM[p]) continue;
    const i=p*4; if(!isGreenish(i)) continue;
    greenM[p]=1;
    if(x>0)st2.push(x-1,y); if(x<W-1)st2.push(x+1,y);
    if(y>0)st2.push(x,y-1); if(y<H-1)st2.push(x,y+1);
  }
  let gCount=0; for(let p=0;p<N;p++) gCount+=greenM[p];
  console.log('绿底像素: '+gCount+' ('+(gCount/N*100).toFixed(1)+'%)  ← 剩下的就是“内容”（气泡+箭头+字）');

  // ---------- 2.5) 形态学：腐蚀掉原图圆角轮廓的残留描边 ----------
  // keep = 既不在白底也不在绿底 → 真内容（气泡/箭头/字）。
  // 原图圆角边界上会留下一圈 1~2px 的浅色混合像素，腐蚀 2px 即可抹掉，
  // 而中间的内容块面积大，腐蚀 2px 只缩 0.2%。
  let cur=new Uint8Array(N);
  for(let p=0;p<N;p++) cur[p]=(!whiteM[p]&&!greenM[p])?1:0;
  for(let it=0;it<2;it++){
    const nx=new Uint8Array(N);
    for(let y=1;y<H-1;y++)for(let x=1;x<W-1;x++){
      const p=y*W+x;
      if(cur[p]&&cur[p-1]&&cur[p+1]&&cur[p-W]&&cur[p+W]) nx[p]=1;
    }
    cur=nx;
  }
  const core=cur;
  const dil=new Uint8Array(N);           // 膨胀 1px 用作柔边
  for(let y=0;y<H;y++)for(let x=0;x<W;x++){
    const p=y*W+x;
    if(core[p]){ dil[p]=1; continue; }
    if((x>0&&core[p-1])||(x<W-1&&core[p+1])||(y>0&&core[p-W])||(y<H-1&&core[p+W])) dil[p]=1;
  }
  let cCnt=0; for(let p=0;p<N;p++) cCnt+=core[p];
  console.log('前景内容像素: '+cCnt+' ('+(cCnt/N*100).toFixed(1)+'%)');

  // ---------- 3) 生成三份像素数据 ----------
  // (a) 圆角图标：白底→透明（带柔和过渡），绿底保留
  // (b) 方形图标：白底→填绿（满铺）
  // (c) 自适应前景：绿底+白底 全透明，只留内容
  const mk=(mode)=>{
    const c=createCanvas(W,H),x2=c.getContext('2d');
    const im=x2.createImageData(W,H), o=im.data;
    // 采样背景绿（用于方形铺底）
    let br=0,bg=0,bb=0,bn=0;
    for(let p=0;p<N;p++){ if(greenM[p]){ const i=p*4; br+=d[i];bg+=d[i+1];bb+=d[i+2];bn++; } }
    br=Math.round(br/bn); bg=Math.round(bg/bn); bb=Math.round(bb/bn);
    for(let p=0;p<N;p++){
      const i=p*4, r=d[i], g=d[i+1], b=d[i+2];
      let a=255, R=r,G=g,B=b;
      if(mode==='foreground'){
        a = core[p] ? 255 : (dil[p] ? 140 : 0);
      } else if(whiteM[p]){
        const mn=Math.min(r,g,b);
        a = clamp(Math.round((235-mn)*255/30),0,255);     // 圆角过渡
        if(mode==='square'){ a=255; R=br;G=bg;B=bb; }     // 方形：铺满绿
      }
      o[i]=R; o[i+1]=G; o[i+2]=B; o[i+3]=a;
    }
    x2.putImageData(im,0,0);
    return {canvas:c, bg:[br,bg,bb]};
  };

  const rounded=mk('rounded');     // 圆角 + 透明外
  const square =mk('square');      // 满铺方形
  const fore   =mk('foreground');  // 只留内容
  const BG=rounded.bg;
  console.log('背景绿采样: rgb('+BG.join(',')+')');

  // ---------- 4) 裁到内容边界 ----------
  function tightBox(canvas, ignoreGreen){
    const x2=canvas.getContext('2d'), im=x2.getImageData(0,0,W,H).data;
    let x0=W,y0=H,x1=-1,y1=-1;
    for(let y=0;y<H;y++)for(let x=0;x<W;x++){
      const i=(y*W+x)*4;
      if(im[i+3]<8) continue;                       // 透明
      if(ignoreGreen){ const r=im[i],g=im[i+1],b=im[i+2];
        if((g-r)>40 && (g-b)>20) continue; }        // 纯绿底不算内容
      if(x<x0)x0=x; if(y<y0)y0=y; if(x>x1)x1=x; if(y>y1)y1=y;
    }
    return {x:x0,y:y0,w:x1-x0+1,h:y1-y0+1};
  }
  const boxR=tightBox(rounded.canvas,false);
  const boxF=tightBox(fore.canvas,false);
  console.log('圆角图标内容框: '+JSON.stringify(boxR));
  console.log('自适应前景内容框: '+JSON.stringify(boxF));

  function save(canvas,file,w,h){
    const c=createCanvas(w,h),x2=c.getContext('2d');
    x2.imageSmoothingEnabled=true; x2.imageSmoothingQuality='high';
    x2.drawImage(canvas,0,0,w,h);
    fs.writeFileSync(path.join(OUT,file),c.toBuffer('image/png'));
  }
  function cropSave(srcCv,box,file,size,mode){
    const c=createCanvas(size,size),x2=c.getContext('2d');
    x2.imageSmoothingEnabled=true; x2.imageSmoothingQuality='high';
    if(mode==='circle'){ x2.beginPath(); x2.arc(size/2,size/2,size/2,0,Math.PI*2); x2.clip(); }
    x2.drawImage(srcCv,box.x,box.y,box.w,box.h,0,0,size,size);
    fs.writeFileSync(path.join(OUT,file),c.toBuffer('image/png'));
  }

  // ---------- 5) 输出 ----------
  const boxSq={x:Math.round(W/2-551),y:Math.round(H/2-551),w:1102,h:1102};
  cropSave(rounded.canvas,boxR,'logo-final-1024.png',1024);
  cropSave(rounded.canvas,boxR,'logo-final-512.png',512);
  cropSave(square.canvas, boxSq,'logo-final-square-1024.png',1024);

  // 自适应图标：背景满铺绿 + 前景内容缩到安全区(62%)
  function makeAdaptive(CAN){
    const bgc=createCanvas(CAN,CAN),bx=bgc.getContext('2d');
    const lg=bx.createLinearGradient(0,0,0,CAN);
    lg.addColorStop(0,'rgb('+BG.map(v=>Math.min(255,v+10)).join(',')+')');
    lg.addColorStop(1,'rgb('+BG.join(',')+')');
    bx.fillStyle=lg; bx.fillRect(0,0,CAN,CAN);
    const fgc=createCanvas(CAN,CAN),fx=fgc.getContext('2d');
    const scale=(CAN*0.62)/Math.max(boxF.w,boxF.h);
    const dw=boxF.w*scale, dh=boxF.h*scale;
    fx.imageSmoothingEnabled=true; fx.imageSmoothingQuality='high';
    fx.drawImage(fore.canvas,boxF.x,boxF.y,boxF.w,boxF.h,(CAN-dw)/2,(CAN-dh)/2,dw,dh);
    return {bgc:bgc,fgc:fgc};
  }

  const dens=[['mdpi',48,108],['hdpi',72,162],['xhdpi',96,216],['xxhdpi',144,324],['xxxhdpi',192,432]];
  for(const d of dens){
    const name=d[0],mip=d[1],adp=d[2];
    const mdir=path.join(OUT,'res','mipmap-'+name); fs.mkdirSync(mdir,{recursive:true});
    cropSave(rounded.canvas,boxR,path.join('res','mipmap-'+name,'ic_launcher.png'),mip);
    cropSave(square.canvas,boxSq,path.join('res','mipmap-'+name,'ic_launcher_round.png'),mip,'circle');
    const ddir=path.join(OUT,'res','drawable-'+name); fs.mkdirSync(ddir,{recursive:true});
    const A=makeAdaptive(adp);
    fs.writeFileSync(path.join(ddir,'ic_launcher_background.png'),A.bgc.toBuffer('image/png'));
    fs.writeFileSync(path.join(ddir,'ic_launcher_foreground.png'),A.fgc.toBuffer('image/png'));
  }

  const A4=makeAdaptive(432);
  fs.writeFileSync(path.join(OUT,'ic_launcher_background.png'),A4.bgc.toBuffer('image/png'));
  fs.writeFileSync(path.join(OUT,'ic_launcher_foreground.png'),A4.fgc.toBuffer('image/png'));
  const pv=createCanvas(432,432),px=pv.getContext('2d');
  px.drawImage(A4.bgc,0,0); px.drawImage(A4.fgc,0,0);
  fs.writeFileSync(path.join(OUT,'preview-adaptive.png'),pv.toBuffer('image/png'));
  console.log('已生成 mipmap + 自适应分层: '+dens.map(d=>d[0]+'('+d[1]+'/'+d[2]+')').join(' '));
})();

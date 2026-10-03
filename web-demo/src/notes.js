function parseNotes(text,deck){
  const out={cards:[],skipped:0};const lines=String(text||"").replace(/\r\n?/g,"\n").split("\n");let pq=null;
  const words=s=>s.trim()?s.trim().split(/\s+/).length:0;
  const cap=s=>s?s[0].toUpperCase()+s.slice(1):s;
  const lf=s=>{if(!s)return s;if(s.length>1&&/[A-Z]/.test(s[1]))return s;const first=s.split(/\s+/)[0];if(first.length>1&&/[A-Z]/.test(first.slice(1)))return s;return s[0].toLowerCase()+s.slice(1);};
  const asQ=f=>{f=f.trim();if(f.endsWith("?"))return f;if(words(f)<=5&&!/[“"]/.test(f)){const acr=f.length>1&&f===f.toUpperCase();return "What is "+(acr?f:lf(f))+"?";}return f;};
  const add=(f,b,x)=>{f=f.trim();b=b.trim();if(!f||!b||f.length>200||b.length>220){out.skipped++;return;}let h=0;const key=deck+"|"+f+"|"+b;for(let i=0;i<key.length;i++)h=(h*31+key.charCodeAt(i))|0;out.cards.push(makeCard("notes-"+(h>>>0).toString(16),deck,f,b,x||""));};
  for(const raw of lines){
    let line=raw.replace(/<[^>]+>/g," ").replace(/&nbsp;/g," ").replace(/&amp;/g,"&").replace(/^\s*(?:[-*•·▪◦]|\d+[.)]|[a-z][.)])\s+/,"");
    line=line.split("\t").map(p=>p.replace(/\s+/g," ").trim()).join("\t").trim();
    if(!line||line.startsWith("#"))continue;
    const low=line.toLowerCase();
    let m=line.match(/^(?:q|question)\s*[:.]\s*(.*)$/i);
    if(m&&(low.startsWith("q:")||low.startsWith("q.")||low.startsWith("question:"))){if(pq!==null)out.skipped++;pq=m[1].trim();continue;}
    m=line.match(/^(?:a|answer)\s*[:.]\s*(.*)$/i);
    if(m&&(low.startsWith("a:")||low.startsWith("a.")||low.startsWith("answer:"))){if(pq&&m[1].trim())add(pq,m[1],"");else out.skipped++;pq=null;continue;}
    if(pq!==null){out.skipped++;pq=null;}
    if(line.includes("\t")){const cols=line.split("\t");if(cols.length>=2&&cols[0]&&cols[1])add(asQ(cols[0]),cols[1],cols[2]||"");else out.skipped++;continue;}
    let pair=null;for(const s of[" :: "," - "," – "," — "," = "," -> "," → "]){const i=line.indexOf(s);if(i>0&&i+s.length<line.length){const a=line.slice(0,i).trim(),b=line.slice(i+s.length).trim();if(a&&b&&a.length<=120){pair=[a,b];break;}}}
    if(!pair){const c=line.indexOf(": ");if(c>0&&c<=60){const a=line.slice(0,c).trim(),b=line.slice(c+2).trim();if(a&&b&&words(a)<=8)pair=[a,b];}}
    if(pair){add(asQ(pair[0]),cap(pair[1]),"");continue;}
    m=line.match(/^(.{2,60}?)\s+(is|are)\s+called\s+(.{1,120}?)[.!]?$/i);if(m&&words(m[1])<=8){add("What "+m[2].toLowerCase()+" "+lf(m[1])+" called?",cap(m[3]),line);continue;}
    m=line.match(/^(.{2,60}?)\s+means\s+(.{2,160}?)[.!]?$/i);if(m&&words(m[1])<=8){add("What does "+lf(m[1])+" mean?",cap(m[2]),"");continue;}
    m=line.match(/^(.{2,60}?)\s+(is|are)\s+(.{2,160}?)[.!]?$/i);if(m&&words(m[1])<=6&&words(m[3])<=22){add("What "+m[2].toLowerCase()+" "+lf(m[1])+"?",cap(m[3]),"");continue;}
    out.skipped++;
  }
  return out;
}


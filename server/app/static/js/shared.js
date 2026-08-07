/* Cashbook 共享工具函数 — api, theme, toast, confetti, animate */
function gt(){return localStorage.getItem('token')||sessionStorage.getItem('token')}
if(!gt() && !['/login','/register'].includes(location.pathname))window.location.href='/login';

async function api(url,o={}){
    if(!o.headers)o.headers={};
    o.headers['Authorization']='Bearer '+gt();
    const r=await fetch(url,o);
    const nt=r.headers.get('X-Refresh-Token');
    if(nt){if(localStorage.getItem('rememberMe'))localStorage.setItem('token',nt);else sessionStorage.setItem('token',nt)}
    return r.json()
}

const _theme=localStorage.getItem('theme')||'light';
document.documentElement.setAttribute('data-theme',_theme);
const _ti=document.getElementById('themeIcon');
if(_ti)_ti.className=_theme==='dark'?'ph ph-sun':'ph ph-moon';
function toggleTheme(){
    const d=document.documentElement.getAttribute('data-theme')==='dark';
    document.documentElement.setAttribute('data-theme',d?'light':'dark');
    const el=document.getElementById('themeIcon');
    if(el)el.className=d?'ph ph-moon':'ph ph-sun';
    localStorage.setItem('theme',d?'light':'dark')
}

function animateNumber(el,target,duration=800){
    if(!el)return;
    if(target===0){el.textContent='¥0.00';return}
    const start=performance.now();
    function update(now){
        const p=Math.min((now-start)/duration,1);
        el.textContent='¥'+(target*p).toFixed(2);
        if(p<1)requestAnimationFrame(update)
    }
    requestAnimationFrame(update)
}

function explodeConfetti(x,y){
    const colors=['#7C3AED','#A78BFA','#10B981','#EF4444','#F59E0B','#F472B6','#34D399'];
    for(let i=0;i<24;i++){
        const el=document.createElement('div');
        el.className='confetti-piece';
        el.style.left=x+'px';el.style.top=y+'px';
        el.style.background=colors[Math.floor(Math.random()*colors.length)];
        el.style.setProperty('--tx',(Math.random()-0.5)*240+'px');
        el.style.setProperty('--ty',(Math.random()-1)*240+'px');
        el.style.borderRadius=Math.random()>0.5?'50%':'2px';
        document.body.appendChild(el);
        setTimeout(()=>el.remove(),800)
    }
}

function toast(msg,t){
    const el=document.getElementById('toast');
    if(!el)return;
    el.textContent=msg;
    el.className='toast '+(t||'info')+' show';
    setTimeout(()=>el.classList.remove('show'),2500)
}

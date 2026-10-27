import struct, zlib, sys
def read_png(p):
    d=open(p,'rb').read(); i=8; idat=b''; w=h=0
    while i<len(d):
        ln=struct.unpack('>I',d[i:i+4])[0]; tag=d[i+4:i+8]; data=d[i+8:i+8+ln]
        if tag==b'IHDR': w,h=struct.unpack('>II',data[:8])
        elif tag==b'IDAT': idat+=data
        i+=12+ln
    raw=zlib.decompress(idat); px=bytearray(); stride=w*4; prev=bytearray(stride)
    for y in range(h):
        f=raw[y*(stride+1)]; line=bytearray(raw[y*(stride+1)+1:(y+1)*(stride+1)])
        if f==1:
            for x in range(4,stride): line[x]=(line[x]+line[x-4])&255
        elif f==2:
            for x in range(stride): line[x]=(line[x]+prev[x])&255
        elif f==3:
            for x in range(stride):
                a=line[x-4] if x>=4 else 0
                line[x]=(line[x]+((a+prev[x])>>1))&255
        elif f==4:
            for x in range(stride):
                a=line[x-4] if x>=4 else 0
                c=prev[x-4] if x>=4 else 0
                b=prev[x]; pp=a+b-c
                pa,pb,pc=abs(pp-a),abs(pp-b),abs(pp-c)
                pr=a if (pa<=pb and pa<=pc) else (b if pb<=pc else c)
                line[x]=(line[x]+pr)&255
        px+=line; prev=line
    return w,h,px

def cls(r,g,b,a):
    if a<40: return ' '
    if r>228 and g>228 and b>228: return '#'   # 白页
    if r>200 and g>140 and b<120: return '*'   # 琥珀
    if b>70 and b>r: return '.'                # 靛蓝（底或画格）
    return '+'

p=sys.argv[1]; N=int(sys.argv[2]) if len(sys.argv)>2 else 32
w,h,px=read_png(p)
print(f"{p}  {w}x{h}")
for ty in range(N):
    row=''
    for tx in range(N*2):
        x=min(w-1,int((tx+0.5)/(N*2)*w)); y=min(h-1,int((ty+0.5)/N*h))
        o=(y*w+x)*4
        row+=cls(px[o],px[o+1],px[o+2],px[o+3])
    print('  '+row)

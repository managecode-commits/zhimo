// Copyright © 2026 立方田 <managecode@gmail.com>
// Independent process: no editor APIs, clipboard, network or persistent audio.
#include <windows.h>
#include <windowsx.h>
#include <mmsystem.h>
#include <bcrypt.h>
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <thread>
#include <vector>
#include "zhimo_ime.h"
#include "zhimo_speech.h"

namespace {
HWND window, status, choices, preview;
bool voice = false, busy = false, recording = false, drawing = false;
unsigned revision = 0;
HWAVEIN microphone = nullptr;
WAVEHDR audio_header{};
std::vector<short> audio(16000 * 60);
ULONGLONG record_start = 0;
std::vector<std::vector<POINT>> strokes;
std::vector<std::wstring> candidates;
std::wstring result_text;
std::filesystem::path directory;
const RECT canvas{78, 118, 724, 398};
struct Result { unsigned revision; std::vector<std::wstring> words; std::wstring text, error; };

std::wstring wide(const std::string &text) {
  int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), nullptr, 0);
  if (count <= 0) return {};
  std::wstring output(count, 0);
  MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), output.data(), count);
  return output;
}
std::string utf8(const std::wstring &text) {
  int count = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), nullptr,0,nullptr,nullptr);
  if (count <= 0) return {};
  std::string output(count,0);
  WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS,text.data(),static_cast<int>(text.size()),output.data(),count,nullptr,nullptr);
  return output;
}
void commit(const std::wstring &text) {
  auto bytes = utf8(text);
  if (bytes.empty() || bytes.size() > 16384) return;
  DWORD sent = 0;
  if (WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), bytes.data(), static_cast<DWORD>(bytes.size()), &sent, nullptr)
      && sent == bytes.size()) ExitProcess(0);
  ExitProcess(1);
}
bool verify(const std::filesystem::path &path, const std::string &expected) {
  std::ifstream input(path, std::ios::binary);
  if (!input) return false;
  BCRYPT_ALG_HANDLE algorithm = nullptr; BCRYPT_HASH_HANDLE hash = nullptr;
  if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0) return false;
  bool ok = BCryptCreateHash(algorithm, &hash, nullptr,0,nullptr,0,0) >= 0;
  char buffer[65536];
  while (ok && input) {
    input.read(buffer,sizeof(buffer));
    ok = BCryptHashData(hash,reinterpret_cast<PUCHAR>(buffer),static_cast<ULONG>(input.gcount()),0) >= 0;
  }
  unsigned char digest[32]{};
  ok = ok && !input.bad() && BCryptFinishHash(hash,digest,32,0) >= 0;
  if (hash) BCryptDestroyHash(hash);
  BCryptCloseAlgorithmProvider(algorithm,0);
  const char hex[] = "0123456789abcdef"; std::string actual;
  for (auto byte : digest) { actual += hex[byte >> 4]; actual += hex[byte & 15]; }
  return ok && actual == expected;
}
void clear() {
  ++revision; strokes.clear(); candidates.clear(); result_text.clear();
  KillTimer(window,2);
  SetWindowTextW(status,voice?L"点击开始录音":L"一次写一字 · 写完点选候选 · 支持撤笔重试");
  SendMessageW(choices, LB_RESETCONTENT,0,0); InvalidateRect(window,&canvas,TRUE);
}
void recognize() {
  if (busy || drawing || strokes.empty()) return;
  busy = true;
  auto token = revision;
  auto ink = strokes;
  SetWindowTextW(status,L"正在离线识别…");
  std::thread([token, ink] {
    auto out = std::make_unique<Result>(); out->revision = token;
    static ImeHandwritingHandle* model = nullptr; // worker-only; OS reclaims on exit
    if (!model) model = ime_handwriting_new(utf8((directory/L"models/handwriting/zh-cn/handwriting-zh_CN.model").wstring()).c_str());
    if (!model) out->error = L"手写模型不可用，请重新安装完整包";
    else {
      std::ostringstream json;
      json << "{\"width\":" << canvas.right-canvas.left << ",\"height\":" << canvas.bottom-canvas.top << ",\"strokes\":[";
      for (size_t i=0;i<ink.size();++i) {
        if (i) json << ',';
        json << '[';
        for (size_t j=0;j<ink[i].size();++j) {
          if (j) json << ',';
          json << "{\"x\":" << ink[i][j].x-canvas.left << ",\"y\":" << ink[i][j].y-canvas.top << ",\"time_ms\":" << j << '}';
        }
        json << ']';
      }
      json << "]}";
      const char* raw = ime_handwriting_recognize_json(model,json.str().c_str());
      if (raw) {
        std::string text(raw); size_t at = 0;
        // Model candidates are single characters, serialized by our Rust ABI.
        while ((at = text.find("\"text\":\"",at)) != std::string::npos && out->words.size()<100) {
          at += 8; auto end = text.find('"',at);
          if (end == std::string::npos) break;
          auto word = wide(text.substr(at,end-at));
          if (!word.empty() && word.find(L'\\') == std::wstring::npos) out->words.push_back(word);
          at = end+1;
        }
      }
      if (out->words.empty()) out->error = L"未识别到候选，请撤笔或重写";
    }
    if (PostMessageW(window,WM_APP,0,reinterpret_cast<LPARAM>(out.get()))) out.release();
  }).detach();
}
void stop_recording() {
  if (!recording) return;
  recording = false; waveInStop(microphone); waveInReset(microphone);
  const size_t count = audio_header.dwBytesRecorded / sizeof(short);
  waveInUnprepareHeader(microphone,&audio_header,sizeof(audio_header)); waveInClose(microphone); microphone=nullptr;
  busy = true;
  auto samples = std::vector<float>(count);
  for (size_t i=0;i<count;++i) samples[i]=audio[i]/32768.0f;
  const auto token = revision;
  SetWindowTextW(status,L"正在离线转录，可点击取消");
  std::thread([token, samples=std::move(samples)] {
    auto out=std::make_unique<Result>(); out->revision=token;
    auto model=directory/L"models/speech/ggml-base-q5_1.bin";
    auto vad=directory/L"models/speech/ggml-silero-v5.1.2.bin";
    if (samples.size()<1600) out->error=L"录音太短，请重录";
    else if (!verify(model,"422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898") ||
             !verify(vad,"29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf"))
      out->error=L"语音模型校验失败，请重新安装完整包";
    else {
      auto session=zhimo_speech_create(); char* text=nullptr;
      int code=zhimo_speech_transcribe_options(session,utf8(model.wstring()).c_str(),samples.data(),samples.size(),"auto","",utf8(vad.wstring()).c_str(),&text);
      if (!code && text) out->text=wide(text);
      if (code) out->error=L"转录失败，请检查模型和设备后重试";
      else if (out->text.empty()) out->error=L"未检测到有效语音，请重录";
      zhimo_speech_text_free(text); zhimo_speech_release(session);
    }
    if (PostMessageW(window,WM_APP,0,reinterpret_cast<LPARAM>(out.get()))) out.release();
  }).detach();
}
void start_recording() {
  if (busy || recording) return;
  clear(); SetWindowTextW(preview,L"");
  WAVEFORMATEX format{}; format.wFormatTag=WAVE_FORMAT_PCM; format.nChannels=1;
  format.nSamplesPerSec=16000; format.wBitsPerSample=16; format.nBlockAlign=2; format.nAvgBytesPerSec=32000;
  if (waveInOpen(&microphone,WAVE_MAPPER,&format,0,0,CALLBACK_NULL)!=MMSYSERR_NOERROR) {
    SetWindowTextW(status,L"无法打开默认麦克风，请检查权限及 16 kHz 格式支持"); return;
  }
  audio_header={}; audio_header.lpData=reinterpret_cast<LPSTR>(audio.data());
  audio_header.dwBufferLength=static_cast<DWORD>(audio.size()*2);
  if (waveInPrepareHeader(microphone,&audio_header,sizeof(audio_header))!=MMSYSERR_NOERROR ||
      waveInAddBuffer(microphone,&audio_header,sizeof(audio_header))!=MMSYSERR_NOERROR || waveInStart(microphone)!=MMSYSERR_NOERROR) {
    waveInReset(microphone); waveInUnprepareHeader(microphone,&audio_header,sizeof(audio_header)); waveInClose(microphone); microphone=nullptr;
    SetWindowTextW(status,L"麦克风启动失败"); return;
  }
  recording=true; record_start=GetTickCount64(); SetWindowTextW(status,L"正在录音 · 最长 60 秒 · 点击停止并识别");
}
LRESULT CALLBACK proc(HWND hwnd,UINT message,WPARAM wp,LPARAM lp) {
  switch(message) {
    case WM_MOUSEACTIVATE: return MA_NOACTIVATE;
    case WM_LBUTTONDOWN: {
      POINT p{GET_X_LPARAM(lp),GET_Y_LPARAM(lp)};
      if (!voice && PtInRect(&canvas,p) && strokes.size()<64) { ++revision; drawing=true; strokes.push_back({p}); SetCapture(hwnd); candidates.clear(); SendMessageW(choices,LB_RESETCONTENT,0,0); SetWindowTextW(status,L"正在书写 · 请把一个字写完整，不必按格分开偏旁"); InvalidateRect(hwnd,&canvas,TRUE); }
      return 0;
    }
    case WM_MOUSEMOVE:
      if (drawing && strokes.back().size()<512) {
        POINT p{std::clamp<LONG>(GET_X_LPARAM(lp),canvas.left,canvas.right),std::clamp<LONG>(GET_Y_LPARAM(lp),canvas.top,canvas.bottom)};
        strokes.back().push_back(p); InvalidateRect(hwnd,&canvas,TRUE);
      } return 0;
    case WM_LBUTTONUP:
      if (drawing) { drawing=false; ReleaseCapture(); SetTimer(hwnd,2,400,nullptr); } return 0;
    case WM_CAPTURECHANGED: drawing=false; return 0;
    case WM_TIMER:
      if (wp==2) { KillTimer(hwnd,2); recognize(); }
      if (wp==1 && recording && GetTickCount64()-record_start>=60000) stop_recording();
      return 0;
    case WM_APP: {
      std::unique_ptr<Result> out(reinterpret_cast<Result*>(lp)); busy=false;
      if (out->revision!=revision) { if (!voice) SetTimer(hwnd,2,400,nullptr); return 0; }
      candidates=out->words; result_text=out->text;
      SendMessageW(choices,LB_RESETCONTENT,0,0);
      for (auto &word:candidates) SendMessageW(choices,LB_ADDSTRING,0,reinterpret_cast<LPARAM>(word.c_str()));
      SetWindowTextW(preview,result_text.c_str());
      SetWindowTextW(status,!out->error.empty()?out->error.c_str():voice?L"请检查结果后确认上屏":L"点击候选上屏；滚动查看更多候选");
      return 0;
    }
    case WM_COMMAND:
      if (!voice && LOWORD(wp)>=30 && LOWORD(wp)<38) {
        const wchar_t* punctuation[]={L"，",L"。",L"、",L"；",L"：",L"？",L"！",L"…"};
        commit(punctuation[LOWORD(wp)-30]);
      }
      if (LOWORD(wp)==10 && HIWORD(wp)==LBN_SELCHANGE) {
        auto index=SendMessageW(choices,LB_GETCURSEL,0,0);
        if (index>=0 && static_cast<size_t>(index)<candidates.size()) commit(candidates[index]);
      }
      if (LOWORD(wp)==11) { if (!strokes.empty()) strokes.pop_back(); ++revision; InvalidateRect(hwnd,&canvas,TRUE); SendMessageW(choices,LB_RESETCONTENT,0,0); SetTimer(hwnd,2,400,nullptr); }
      if (LOWORD(wp)==12) clear();
      if (LOWORD(wp)==17) { KillTimer(hwnd,2); recognize(); }
      if (LOWORD(wp)==13) ExitProcess(0);
      if (LOWORD(wp)==14) start_recording();
      if (LOWORD(wp)==15) stop_recording();
      if (LOWORD(wp)==16 && !busy && !recording) commit(result_text);
      return 0;
    case WM_PAINT: {
      PAINTSTRUCT ps{}; HDC dc=BeginPaint(hwnd,&ps);
      if (!voice) {
        FillRect(dc,&canvas,static_cast<HBRUSH>(GetStockObject(WHITE_BRUSH)));
        auto guide=CreatePen(PS_DOT,1,RGB(220,225,233)); auto previous=SelectObject(dc,guide);
        for (int x=canvas.left+20;x<canvas.right;x+=20) { MoveToEx(dc,x,canvas.top,nullptr); LineTo(dc,x,canvas.bottom); }
        for (int y=canvas.top+20;y<canvas.bottom;y+=20) { MoveToEx(dc,canvas.left,y,nullptr); LineTo(dc,canvas.right,y); }
        SelectObject(dc,previous); DeleteObject(guide);
        if (strokes.empty()) {
          RECT hint=canvas; hint.left+=12; hint.top+=10;
          SetBkMode(dc,TRANSPARENT); SetTextColor(dc,RGB(135,145,157));
          DrawTextW(dc,L"在此写一个完整汉字 · 网格不是分字边界",-1,&hint,DT_LEFT|DT_TOP|DT_SINGLELINE);
        }
        auto pen=CreatePen(PS_SOLID,3,RGB(48,55,66)); auto old=SelectObject(dc,pen);
        for (auto &stroke:strokes) if (!stroke.empty()) {
          if (stroke.size()==1) { const auto p=stroke[0]; RECT dot{p.x-2,p.y-2,p.x+2,p.y+2}; auto brush=CreateSolidBrush(RGB(48,55,66)); FillRect(dc,&dot,brush); DeleteObject(brush); }
          MoveToEx(dc,stroke[0].x,stroke[0].y,nullptr); for (auto p:stroke) LineTo(dc,p.x,p.y);
        }
        SelectObject(dc,old); DeleteObject(pen);
      }
      EndPaint(hwnd,&ps); return 0;
    }
    case WM_CLOSE: ExitProcess(0);
  }
  return DefWindowProcW(hwnd,message,wp,lp);
}
}
int main(int argc,char** argv) {
  if (argc!=2 || (std::string(argv[1])!="speech" && std::string(argv[1])!="handwriting")) return 2;
  voice=std::string(argv[1])=="speech";
  wchar_t path[32768]{}; GetModuleFileNameW(nullptr,path,32768); directory=std::filesystem::path(path).parent_path();
  HINSTANCE module=GetModuleHandleW(nullptr);
  WNDCLASSW type{}; type.lpfnWndProc=proc; type.hInstance=module; type.hCursor=LoadCursor(nullptr,IDC_ARROW);
  type.hbrBackground=reinterpret_cast<HBRUSH>(COLOR_BTNFACE+1); type.lpszClassName=L"Zhimo.DesktopPanel.v1";
  if (!RegisterClassW(&type)) return 2;
  window=CreateWindowExW(WS_EX_NOACTIVATE|WS_EX_TOOLWINDOW|WS_EX_TOPMOST,type.lpszClassName,
      voice?L"知墨 · 离线语音输入":L"知墨 · 离线单字手写",WS_POPUP|WS_CAPTION|WS_SYSMENU,
      120,120,voice?600:752,510,nullptr,nullptr,module,nullptr);
  if (!window) return 2;
  auto child=[&](const wchar_t* cls,const wchar_t* text,DWORD style,int x,int y,int width,int height,int id) {
    HWND control=CreateWindowW(cls,text,WS_CHILD|WS_VISIBLE|style,x,y,width,height,window,reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),module,nullptr);
    SendMessageW(control,WM_SETFONT,reinterpret_cast<WPARAM>(GetStockObject(DEFAULT_GUI_FONT)),TRUE); return control;
  };
  status=child(L"STATIC",voice?L"点击开始录音；支持中文／英文；确认后上屏":L"离线单字识别 · 点选候选上屏 · 滚动查看更多",0,voice?14:78,12,voice?555:646,28,0);
  if (!voice) {
    child(L"STATIC",L"单字\n手写",SS_CENTER,8,52,56,52,0);
    child(L"STATIC",L"离线\n识别",SS_CENTER,8,124,56,48,0);
  }
  choices=child(L"LISTBOX",L"",LBS_NOTIFY|WS_HSCROLL|LBS_MULTICOLUMN|LBS_NOINTEGRALHEIGHT,78,42,646,68,10);
  HFONT candidate_font=CreateFontW(-26,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Microsoft YaHei UI");
  SendMessageW(choices,WM_SETFONT,reinterpret_cast<WPARAM>(candidate_font),TRUE);
  SendMessageW(choices,LB_SETCOLUMNWIDTH,60,0);
  preview=child(L"STATIC",L"",0,14,100,550,265,0);
  if (!voice) ShowWindow(preview,SW_HIDE);
  if (voice) ShowWindow(choices,SW_HIDE);
  if (voice) {
    child(L"BUTTON",L"开始录音",0,14,390,120,34,14);
    child(L"BUTTON",L"停止并识别",0,142,390,130,34,15);
    child(L"BUTTON",L"确认上屏",0,280,390,120,34,16);
  } else {
    const wchar_t* punctuation[]={L"，",L"。",L"、",L"；",L"：",L"？",L"！",L"…"};
    for (int i=0;i<8;++i) child(L"BUTTON",punctuation[i],0,78+i*34,410,34,34,30+i);
    child(L"BUTTON",L"撤一笔",0,362,410,82,34,11);
    child(L"BUTTON",L"重写",0,450,410,74,34,12);
    child(L"BUTTON",L"重新识别",0,530,410,92,34,17);
  }
  child(L"BUTTON",L"关闭",0,voice?438:628,voice?390:410,voice?120:96,34,13);
  SetTimer(window,1,100,nullptr);
  ShowWindow(window,SW_SHOWNOACTIVATE);
  MSG message{}; while (GetMessageW(&message,nullptr,0,0)>0) { TranslateMessage(&message); DispatchMessageW(&message); }
  return 0;
}

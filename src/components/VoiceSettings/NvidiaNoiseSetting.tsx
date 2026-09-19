import { useEffect, useState } from 'react';
import { Switch, Typography } from 'antd';
import { tl } from '../../i18n';
import { nvidiaNoiseDevice, nvidiaNoiseMode, setNvidiaNoiseMode } from '../../services/voice/nvidiaNoise';
import { webrtcClient } from '../../services';
import { message } from 'antd';

export function NvidiaNoiseSetting({ lobby = false }: { lobby?: boolean }) {
  const [available, setAvailable] = useState(false);
  const [enabled, setEnabled] = useState(nvidiaNoiseMode(lobby) === 'auto');
  useEffect(() => {
    let disposed = false;
    const refresh = () => { void nvidiaNoiseDevice().then(device => { if (!disposed) setAvailable(!!device); }).catch(() => { if (!disposed) setAvailable(false); }); };
    refresh();
    navigator.mediaDevices?.addEventListener('devicechange', refresh);
    return () => { disposed = true; navigator.mediaDevices?.removeEventListener('devicechange', refresh); };
  }, []);
  return <div style={{ display: 'flex', alignItems: 'center', gap: 12, margin: '12px 14px', minWidth: 0 }}>
    <div style={{ flex: 1, minWidth: 0 }}>
      <Typography.Text>{tl('NVIDIA AI 语音降噪', 'NVIDIA AI Noise Removal')}</Typography.Text>
      <div><Typography.Text type="secondary">{available ? 'NVIDIA Broadcast / RTX Voice' : tl('未检测到 NVIDIA Broadcast / RTX Voice 麦克风', 'NVIDIA Broadcast / RTX Voice microphone unavailable')}</Typography.Text></div>
    </div>
    <Switch style={{ flexShrink: 0 }} checked={enabled && available} disabled={!available} onChange={value => {
      setEnabled(value); setNvidiaNoiseMode(value ? 'auto' : 'off', lobby);
      void webrtcClient.refreshMicrophoneProcessing().catch(() => message.error(tl('切换降噪失败，请重新开启麦克风', 'Could not switch noise removal. Re-enable the microphone')));
    }} />
  </div>;
}

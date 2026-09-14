import { audioDevices } from './audioDevices';

export type NvidiaNoiseMode = 'auto' | 'off';
let lobbyOverride: NvidiaNoiseMode | null = null;
export function nvidiaNoiseMode(lobby = true): NvidiaNoiseMode {
  const globalMode = localStorage.getItem('mctier_nvidia_noise') === 'off' ? 'off' : 'auto';
  return lobby ? lobbyOverride ?? globalMode : globalMode;
}
export function setNvidiaNoiseMode(mode: NvidiaNoiseMode, lobby: boolean): void {
  if (lobby) lobbyOverride = mode;
  else localStorage.setItem('mctier_nvidia_noise', mode);
  window.dispatchEvent(new Event('mctier-audio-processing-changed'));
}
export function resetLobbyNoiseMode(): void { lobbyOverride = null; }
export async function nvidiaNoiseDevice(): Promise<MediaDeviceInfo | undefined> {
  const devices = await navigator.mediaDevices.enumerateDevices();
  return devices.find(device => device.kind === 'audioinput' && /NVIDIA Broadcast|NVIDIA RTX Voice/i.test(device.label));
}
export async function microphoneConstraints(): Promise<MediaTrackConstraints> {
  const nvidia = nvidiaNoiseMode() === 'auto' ? await nvidiaNoiseDevice() : undefined;
  const preferred = nvidia?.deviceId || audioDevices.getInputDeviceId();
  return {
    echoCancellation: true,
    noiseSuppression: !nvidia,
    autoGainControl: true,
    ...(preferred ? { deviceId: nvidia ? { exact: preferred } : { ideal: preferred } } : {}),
  };
}

export async function captureVoiceStream(): Promise<MediaStream> {
  let stream = await navigator.mediaDevices.getUserMedia({ audio: await microphoneConstraints(), video: false });
  // Permission may reveal device labels for the first time. Re-check so the
  // initial recording also uses the NVIDIA device when it becomes visible.
  try {
    const device = nvidiaNoiseMode() === 'auto' ? await nvidiaNoiseDevice() : undefined;
    if (device && stream.getAudioTracks()[0]?.getSettings().deviceId !== device.deviceId) {
      const next = await navigator.mediaDevices.getUserMedia({ audio: { ...await microphoneConstraints(), deviceId: { exact: device.deviceId } } });
      stream.getTracks().forEach(track => track.stop());
      stream = next;
    }
    return stream;
  } catch (error) {
    stream.getTracks().forEach(track => track.stop());
    throw error;
  }
}

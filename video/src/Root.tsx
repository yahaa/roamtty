import {Composition} from 'remotion';
import {RoamttyDemo, demoDuration} from './RoamttyDemo';

// 1920x1080 at 30 fps: the format Google Play and YouTube expect for a landscape demo.
export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition
        id="PlayReview"
        component={RoamttyDemo}
        durationInFrames={demoDuration(30)}
        fps={30}
        width={1920}
        height={1080}
        defaultProps={{variant: 'review' as const, lang: 'en' as const}}
      />
      <Composition
        id="SiteEn"
        component={RoamttyDemo}
        durationInFrames={demoDuration(30)}
        fps={30}
        width={1920}
        height={1080}
        defaultProps={{variant: 'site' as const, lang: 'en' as const}}
      />
      <Composition
        id="SiteZh"
        component={RoamttyDemo}
        durationInFrames={demoDuration(30)}
        fps={30}
        width={1920}
        height={1080}
        defaultProps={{variant: 'site' as const, lang: 'zh' as const}}
      />
    </>
  );
};

import {createRoot} from 'react-dom/client';
import {ThemeProvider} from '@montage-ui/core';
import '@montage-ui/core/global.css';
import '@montage-ui/core/theme.css';
import {App} from './App';
import {Print} from './Print';
import {ScreenBoundary} from './ScreenBoundary';
import './styles.css';

createRoot(document.getElementById('root')!).render(<ScreenBoundary><ThemeProvider enableDarkMode>{new URLSearchParams(location.search).get('print') ? <Print id={new URLSearchParams(location.search).get('print')!}/> : <App/>}</ThemeProvider></ScreenBoundary>);

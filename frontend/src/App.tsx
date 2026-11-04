import { BrowserRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from './api/queryClient';
import { useAuthBootstrap } from './features/auth/useAuth';
import { AppRoutes } from './routes/AppRoutes';

/**
 * Bootstrap must run inside the router, because a failed session restore
 * navigates. Hence the inner component rather than doing it in App directly.
 */
const AppShell = () => {
  useAuthBootstrap();
  return <AppRoutes />;
};

export const App = () => (
  <QueryClientProvider client={queryClient}>
    <BrowserRouter>
      <AppShell />
    </BrowserRouter>
  </QueryClientProvider>
);

export default App;

using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;

namespace StockMovementAnalyzer.Windows;

public partial class App : Application
{
	private const string ProductName = "Stock Movement Analyzer";
	private static readonly string InstallDirectory = Path.Combine(
		Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "StockMovementAnalyzer");
	private static readonly string InstalledApp = Path.Combine(InstallDirectory, "StockMovementAnalyzer.Windows.exe");

	protected override void OnStartup(StartupEventArgs eventArgs)
	{
		base.OnStartup(eventArgs);
		var executableName = Path.GetFileNameWithoutExtension(Environment.ProcessPath) ?? string.Empty;
		if (eventArgs.Args.Contains("/uninstall", StringComparer.OrdinalIgnoreCase) || executableName.Contains("Uninstall", StringComparison.OrdinalIgnoreCase))
		{
			Uninstall();
			Shutdown();
			return;
		}
		if (eventArgs.Args.Contains("/install", StringComparer.OrdinalIgnoreCase) || executableName.Contains("Setup", StringComparison.OrdinalIgnoreCase))
		{
			Install(eventArgs.Args.Contains("/silent", StringComparer.OrdinalIgnoreCase));
			Shutdown();
			return;
		}
		new MainWindow().Show();
	}

	private static void Install(bool silent)
	{
		try
		{
			Directory.CreateDirectory(InstallDirectory);
			File.Copy(Environment.ProcessPath!, InstalledApp, true);
			File.Copy(Environment.ProcessPath!, Path.Combine(InstallDirectory, "Uninstall.exe"), true);
			CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), "Programs", $"{ProductName}.lnk"), InstalledApp);
			CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), $"{ProductName}.lnk"), InstalledApp);
			RefreshShellIcons();
			if (!silent)
			{
				MessageBox.Show("Installed for the current user. Ollama and model downloads remain separate, explicit free local components.", $"{ProductName} setup");
				Process.Start(new ProcessStartInfo(InstalledApp) { UseShellExecute = true });
			}
		}
		catch (Exception error) { MessageBox.Show(error.Message, $"{ProductName} setup", MessageBoxButton.OK, MessageBoxImage.Error); }
	}

	private static void Uninstall()
	{
		DeleteShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), "Programs", $"{ProductName}.lnk"));
		DeleteShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), $"{ProductName}.lnk"));
		RefreshShellIcons();
		var cleanup = Path.Combine(Path.GetTempPath(), $"stock-analyzer-uninstall-{Guid.NewGuid():N}.cmd");
		File.WriteAllText(cleanup, $"@echo off\r\nping 127.0.0.1 -n 2 > nul\r\nrmdir /s /q \"{InstallDirectory}\"\r\ndel /q \"%~f0\"\r\n");
		Process.Start(new ProcessStartInfo("cmd.exe", $"/c \"{cleanup}\"") { CreateNoWindow = true, UseShellExecute = false });
		MessageBox.Show("Uninstalled for the current user. Local watchlist settings were preserved.", ProductName);
	}

	private static void CreateShortcut(string path, string target)
	{
		Directory.CreateDirectory(Path.GetDirectoryName(path)!);
		var shellType = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("Windows shortcut service is unavailable.");
		dynamic shell = Activator.CreateInstance(shellType)!;
		dynamic shortcut = shell.CreateShortcut(path);
		shortcut.TargetPath = target;
		shortcut.WorkingDirectory = InstallDirectory;
		shortcut.IconLocation = $"{target},0";
		shortcut.Description = ProductName;
		shortcut.Save();
		Marshal.FinalReleaseComObject(shortcut);
		Marshal.FinalReleaseComObject(shell);
	}

	private static void DeleteShortcut(string path) { if (File.Exists(path)) File.Delete(path); }

	private static void RefreshShellIcons()
	{
		const uint SHCNE_ASSOCCHANGED = 0x08000000;
		const uint SHCNF_IDLIST = 0x0000;
		NativeMethods.SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, IntPtr.Zero, IntPtr.Zero);
	}

	private static class NativeMethods
	{
		[DllImport("shell32.dll")]
		internal static extern void SHChangeNotify(uint eventId, uint flags, IntPtr item1, IntPtr item2);
	}
}


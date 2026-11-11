using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

class Launcher
{
    [STAThread]
    static void Main(string[] args)
    {
        string folder = AppDomain.CurrentDomain.BaseDirectory;
        string python = Path.Combine(folder, @"vendor\cleanyourtv\tools\venv\Scripts\pythonw.exe");
        string app = Path.Combine(folder, "app.py");
        if (!File.Exists(python) || !File.Exists(app))
        {
            MessageBox.Show("Распакуйте всю папку Sokol_CleanTV и запускайте EXE внутри неё.", "Sokol_CleanTV");
            return;
        }
        string parameters = "\"" + app + "\"";
        bool screenshot = args.Length == 2 && args[0] == "--screenshot";
        if (screenshot)
        {
            if (args[1].Contains("\"")) return;
            parameters += " --screenshot \"" + Path.GetFullPath(args[1]) + "\"";
        }
        Process process = Process.Start(new ProcessStartInfo(python, parameters)
        {
            WorkingDirectory = folder,
            UseShellExecute = false,
            CreateNoWindow = true
        });
        if (screenshot)
        {
            process.WaitForExit();
            Environment.ExitCode = process.ExitCode;
        }
    }
}
